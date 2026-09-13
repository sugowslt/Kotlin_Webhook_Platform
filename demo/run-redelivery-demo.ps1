param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$OperatorToken = "local-demo-operator-token-not-secret"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
if ($OperatorToken.Length -lt 32) {
    throw "Operator token must contain at least 32 characters"
}
$previousOperatorToken = $env:HOOK_RELAY_OPERATOR_TOKEN
$env:HOOK_RELAY_OPERATOR_TOKEN = $OperatorToken

function Invoke-DatabaseScalar {
    param(
        [Parameter(Mandatory)]
        [string]$Query
    )

    $result = docker compose exec -T postgres `
        psql -U hook_relay -d hook_relay -tAc $Query
    if ($LASTEXITCODE -ne 0) {
        throw "Delivery status query failed"
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

function Wait-DeliveryStatus {
    param(
        [Parameter(Mandatory)]
        [string]$DeliveryId,
        [Parameter(Mandatory)]
        [string]$ExpectedStatus
    )

    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $status = Invoke-DatabaseScalar `
            -Query "SELECT status FROM webhook_deliveries WHERE id = '$DeliveryId';"
        if ($status -eq $ExpectedStatus) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Delivery did not reach $ExpectedStatus within 30 seconds"
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
    $eventType = "demo.redelivery.$runId"
    $subscriptionBody = @{
        name = "redelivery-demo-$runId"
        endpointUrl = "http://webhook-receiver:8081/webhooks/fail-once"
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
        -Headers @{ "Idempotency-Key" = "redelivery-demo-$runId" } `
        -ContentType "application/json" `
        -Body $eventBody

    $deliveryId = Invoke-DatabaseScalar `
        -Query "SELECT id FROM webhook_deliveries WHERE event_id = '$($event.eventId)';"
    Wait-DeliveryStatus -DeliveryId $deliveryId -ExpectedStatus "FAILED"

    $redelivery = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/deliveries/$deliveryId/redeliveries" `
        -Headers @{ "Authorization" = "Bearer $OperatorToken" }
    if ($redelivery.status -ne "PENDING") {
        throw "Redelivery request was not accepted as PENDING"
    }

    Wait-DeliveryStatus -DeliveryId $deliveryId -ExpectedStatus "SUCCEEDED"
    $attemptCount = Invoke-DatabaseScalar `
        -Query "SELECT attempt_count FROM webhook_deliveries WHERE id = '$deliveryId';"
    $outcomes = Invoke-DatabaseScalar `
        -Query "SELECT string_agg(outcome, ',' ORDER BY attempt_number) FROM webhook_delivery_attempts WHERE delivery_id = '$deliveryId';"
    if ([int]$attemptCount -ne 2 -or $outcomes -ne "FAILED,SUCCEEDED") {
        throw "Unexpected attempt history: count=$attemptCount outcomes=$outcomes"
    }

    Write-Host ""
    Write-Host "Manual redelivery completed"
    Write-Host "subscriptionId=$($subscription.id)"
    Write-Host "eventId=$($event.eventId)"
    Write-Host "deliveryId=$deliveryId"
    Write-Host "attemptCount=$attemptCount"
    Write-Host "outcomes=$outcomes"
    Write-Host ""
    $receiverLogs = docker compose logs --tail 100 webhook-receiver
    if ($LASTEXITCODE -ne 0) {
        throw "Webhook receiver log query failed"
    }
    $deliveryLogs = @($receiverLogs | Select-String -SimpleMatch $deliveryId)
    if ($deliveryLogs.Count -ne 2) {
        throw "Expected two receiver log entries, but found $($deliveryLogs.Count)"
    }
    $deliveryLogs | ForEach-Object { Write-Host $_.Line }
} finally {
    Pop-Location
    $env:HOOK_RELAY_OPERATOR_TOKEN = $previousOperatorToken
}
