param(
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$appKilled = $false

function Invoke-DatabaseScalar {
    param(
        [Parameter(Mandatory)]
        [string]$Query
    )

    $result = docker compose exec -T postgres `
        psql -U hook_relay -d hook_relay -tAc $Query
    if ($LASTEXITCODE -ne 0) {
        throw "Database query failed"
    }
    $values = @(
        $result |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -ne "" }
    )
    if ($values.Count -ne 1) {
        throw "Expected one database value, but found $($values.Count)"
    }
    return [string]$values[0]
}

function Wait-ReceiverHeld {
    param(
        [Parameter(Mandatory)]
        [string]$DeliveryId
    )

    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $logs = docker compose logs --tail 100 webhook-receiver
        if ($LASTEXITCODE -ne 0) {
            throw "Webhook receiver log query failed"
        }
        $held = $logs |
            Select-String -SimpleMatch $DeliveryId |
            Select-String -SimpleMatch '"responseStatus": "HELD"'
        if ($null -ne $held) {
            return
        }
        Start-Sleep -Milliseconds 200
    }
    throw "Webhook receiver did not hold delivery within 6 seconds"
}

function Wait-DeliveryStatus {
    param(
        [Parameter(Mandatory)]
        [string]$DeliveryId,
        [Parameter(Mandatory)]
        [string]$ExpectedStatus
    )

    for ($attempt = 0; $attempt -lt 70; $attempt++) {
        $status = Invoke-DatabaseScalar `
            -Query "SELECT status FROM webhook_deliveries WHERE id = '$DeliveryId';"
        if ($status -eq $ExpectedStatus) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Delivery did not reach $ExpectedStatus within 70 seconds"
}

function Wait-ApplicationHealthy {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health"
            if ($health.status -eq "UP") {
                return
            }
        } catch {
            Start-Sleep -Seconds 1
            continue
        }
        Start-Sleep -Seconds 1
    }
    throw "Application did not become healthy within 60 seconds"
}

Push-Location $repositoryRoot
try {
    docker compose up --build --detach
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose startup failed"
    }
    docker compose restart webhook-receiver
    if ($LASTEXITCODE -ne 0) {
        throw "Webhook receiver restart failed"
    }
    docker compose up --wait
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose health check failed"
    }

    $runId = [Guid]::NewGuid().ToString("N")
    $eventType = "demo.crash.$runId"
    $subscriptionBody = @{
        name = "crash-demo-$runId"
        endpointUrl = "http://webhook-receiver:8081/webhooks/hold-once"
        eventTypes = @($eventType)
    } | ConvertTo-Json -Compress
    $subscription = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/subscriptions" `
        -ContentType "application/json" `
        -Body $subscriptionBody

    $eventBody = @{
        orderId = $runId
        occurredAt = [DateTimeOffset]::UtcNow.ToString("O")
    } | ConvertTo-Json -Compress
    $event = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/events/$eventType" `
        -Headers @{ "Idempotency-Key" = "crash-demo-$runId" } `
        -ContentType "application/json" `
        -Body $eventBody

    $deliveryId = Invoke-DatabaseScalar `
        -Query "SELECT id FROM webhook_deliveries WHERE event_id = '$($event.eventId)';"
    Wait-ReceiverHeld -DeliveryId $deliveryId

    $claimedState = Invoke-DatabaseScalar `
        -Query "SELECT concat_ws('|', status, attempt_count) FROM webhook_deliveries WHERE id = '$deliveryId';"
    if ($claimedState -ne "PROCESSING|0") {
        throw "Delivery was not claimed before termination: $claimedState"
    }
    $leaseUntil = Invoke-DatabaseScalar `
        -Query "SELECT to_char(lease_until AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS') FROM webhook_deliveries WHERE id = '$deliveryId';"
    $leaseUntilEpoch = [long](Invoke-DatabaseScalar `
        -Query "SELECT floor(extract(epoch FROM lease_until))::bigint FROM webhook_deliveries WHERE id = '$deliveryId';")

    docker compose kill app
    if ($LASTEXITCODE -ne 0) {
        throw "Application termination failed"
    }
    $appKilled = $true

    $terminatedState = Invoke-DatabaseScalar `
        -Query "SELECT concat_ws('|', d.status, d.attempt_count, (SELECT count(*) FROM webhook_delivery_attempts a WHERE a.delivery_id = d.id)) FROM webhook_deliveries d WHERE d.id = '$deliveryId';"
    if ($terminatedState -ne "PROCESSING|0|0") {
        throw "Unexpected state after termination: $terminatedState"
    }

    docker compose start app
    if ($LASTEXITCODE -ne 0) {
        throw "Application restart failed"
    }
    $appKilled = $false
    Wait-ApplicationHealthy
    Wait-DeliveryStatus -DeliveryId $deliveryId -ExpectedStatus "SUCCEEDED"

    $recoveredState = Invoke-DatabaseScalar `
        -Query "SELECT concat_ws('|', d.status, d.attempt_count, (SELECT string_agg(outcome, ',' ORDER BY attempt_number) FROM webhook_delivery_attempts a WHERE a.delivery_id = d.id)) FROM webhook_deliveries d WHERE d.id = '$deliveryId';"
    if ($recoveredState -ne "SUCCEEDED|1|SUCCEEDED") {
        throw "Unexpected recovered state: $recoveredState"
    }
    $recoveryStartedEpoch = [long](Invoke-DatabaseScalar `
        -Query "SELECT floor(extract(epoch FROM started_at))::bigint FROM webhook_delivery_attempts WHERE delivery_id = '$deliveryId' AND attempt_number = 1;")
    if ($recoveryStartedEpoch -lt $leaseUntilEpoch) {
        throw "Delivery was reclaimed before its lease expired"
    }

    $receiverLogs = docker compose logs --tail 100 webhook-receiver
    if ($LASTEXITCODE -ne 0) {
        throw "Webhook receiver log query failed"
    }
    $deliveryLogs = @($receiverLogs | Select-String -SimpleMatch $deliveryId)
    if ($deliveryLogs.Count -ne 2) {
        throw "Expected two receiver log entries, but found $($deliveryLogs.Count)"
    }
    if ($null -eq ($deliveryLogs | Select-String -SimpleMatch '"responseStatus": "HELD"')) {
        throw "Held receiver request was not recorded"
    }
    if ($null -eq ($deliveryLogs | Select-String -SimpleMatch '"responseStatus": 204')) {
        throw "Recovered receiver request was not recorded"
    }

    Write-Host ""
    Write-Host "Worker crash recovery completed"
    Write-Host "subscriptionId=$($subscription.id)"
    Write-Host "eventId=$($event.eventId)"
    Write-Host "deliveryId=$deliveryId"
    Write-Host "leaseUntilUtc=$leaseUntil"
    Write-Host "recoveryStartedAfterLease=true"
    Write-Host "stateAfterKill=$terminatedState"
    Write-Host "recoveredState=$recoveredState"
    Write-Host "receiverRequestCount=$($deliveryLogs.Count)"
    Write-Host ""
    $deliveryLogs | ForEach-Object { Write-Host $_.Line }
} finally {
    if ($appKilled) {
        docker compose start app | Out-Null
    }
    Pop-Location
}
