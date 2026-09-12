param(
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot

Push-Location $repositoryRoot
try {
    docker compose up --build --wait
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose startup failed"
    }

    $runId = [Guid]::NewGuid().ToString("N")
    $subscriptionBody = @{
        name = "local-demo-$runId"
        endpointUrl = "http://webhook-receiver:8081/webhooks"
        eventTypes = @("demo.created")
    } | ConvertTo-Json -Compress
    $subscription = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/subscriptions" `
        -ContentType "application/json" `
        -Body $subscriptionBody

    $eventBody = @{
        orderId = $runId
        amount = 25000
        occurredAt = [DateTimeOffset]::UtcNow.ToString("O")
    } | ConvertTo-Json -Compress
    $event = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/events/demo.created" `
        -Headers @{ "Idempotency-Key" = "demo-$runId" } `
        -ContentType "application/json" `
        -Body $eventBody

    $expectedDeliveries = [int]$event.deliveryCount
    $delivered = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $query = "SELECT count(*) FROM webhook_deliveries WHERE event_id = '$($event.eventId)' AND status = 'SUCCEEDED';"
        $succeededDeliveries = docker compose exec -T postgres `
            psql -U hook_relay -d hook_relay -tAc $query
        if ($LASTEXITCODE -ne 0) {
            throw "Delivery status query failed"
        }
        if ([int]$succeededDeliveries -eq $expectedDeliveries) {
            $delivered = $true
            break
        }
        Start-Sleep -Seconds 1
    }

    if (-not $delivered) {
        throw "Webhook delivery did not complete within 30 seconds"
    }

    $metrics = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/actuator/prometheus"
    if ($metrics.Content -notmatch '(?m)^hookrelay_delivery_processing_seconds_count\{[^}]*outcome="succeeded"[^}]*\}') {
        throw "Succeeded delivery metric was not exposed"
    }

    Write-Host ""
    Write-Host "Demo delivery completed"
    Write-Host "subscriptionId=$($subscription.id)"
    Write-Host "eventId=$($event.eventId)"
    Write-Host "deliveryCount=$expectedDeliveries"
    Write-Host ""
    docker compose logs --tail 10 webhook-receiver
    Write-Host ""
    Write-Host "Grafana:    http://127.0.0.1:3000/d/hook-relay-overview"
    Write-Host "Prometheus: http://127.0.0.1:9090"
    Write-Host "API health: http://127.0.0.1:8080/actuator/health"
    Write-Host ""
    Write-Host "Stop: docker compose down"
    Write-Host "Reset data: docker compose down -v"
} finally {
    Pop-Location
}
