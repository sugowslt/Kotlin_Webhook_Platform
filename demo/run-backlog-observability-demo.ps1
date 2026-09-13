param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PrometheusUrl = "http://127.0.0.1:9090",
    [ValidateRange(40, 500)]
    [int]$EventCount = 100
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot

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

function Invoke-PrometheusScalar {
    param(
        [Parameter(Mandatory)]
        [string]$Query
    )

    $encodedQuery = [Uri]::EscapeDataString($Query)
    $response = Invoke-RestMethod -Uri "$PrometheusUrl/api/v1/query?query=$encodedQuery"
    if ($response.status -ne "success") {
        throw "Prometheus query failed: $Query"
    }
    $results = @($response.data.result)
    if ($results.Count -eq 0) {
        return 0.0
    }
    if ($results.Count -ne 1) {
        throw "Expected one Prometheus value, but found $($results.Count): $Query"
    }
    return [double]::Parse(
        [string]$results[0].value[1],
        [Globalization.CultureInfo]::InvariantCulture
    )
}

function Get-DeliveryState {
    param(
        [Parameter(Mandatory)]
        [string]$EventType
    )

    $query = @"
SELECT concat_ws('|',
    count(*),
    count(*) FILTER (WHERE (
        d.status IN ('PENDING', 'RETRY_WAIT') AND d.next_attempt_at <= clock_timestamp()
    ) OR (
        d.status = 'PROCESSING' AND d.lease_until <= clock_timestamp()
    )),
    count(*) FILTER (WHERE d.status = 'PROCESSING' AND d.lease_until > clock_timestamp()),
    count(*) FILTER (WHERE d.status = 'PROCESSING' AND d.lease_until IS NULL),
    count(*) FILTER (WHERE d.status = 'SUCCEEDED'),
    count(*) FILTER (WHERE d.status IN ('FAILED', 'DEAD_LETTER'))
)
FROM webhook_deliveries d
JOIN webhook_events e ON e.id = d.event_id
WHERE e.event_type = '$EventType';
"@
    $values = (Invoke-DatabaseScalar -Query $query) -split '\|'
    if ($values.Count -ne 6) {
        throw "Expected six delivery state values, but found $($values.Count)"
    }
    return @{
        Total = [int]$values[0]
        Claimable = [int]$values[1]
        Leased = [int]$values[2]
        Stalled = [int]$values[3]
        Succeeded = [int]$values[4]
        TerminalFailure = [int]$values[5]
    }
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
    $eventType = "demo.backlog.$runId"
    $subscriptionBody = @{
        name = "backlog-demo-$runId"
        endpointUrl = "http://webhook-receiver:8081/webhooks/delay"
        eventTypes = @($eventType)
    } | ConvertTo-Json -Compress
    $subscription = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/subscriptions" `
        -ContentType "application/json" `
        -Body $subscriptionBody

    $claimCountBefore = Invoke-PrometheusScalar `
        -Query 'sum(hookrelay_delivery_claim_seconds_count{outcome="succeeded"}) or vector(0)'
    $claimSecondsBefore = Invoke-PrometheusScalar `
        -Query 'sum(hookrelay_delivery_claim_seconds_sum{outcome="succeeded"}) or vector(0)'

    $measurement = [Diagnostics.Stopwatch]::StartNew()
    $ingest = [Diagnostics.Stopwatch]::StartNew()
    for ($index = 0; $index -lt $EventCount; $index++) {
        $eventBody = @{
            orderId = "$runId-$index"
            sequence = $index
            occurredAt = [DateTimeOffset]::UtcNow.ToString("O")
        } | ConvertTo-Json -Compress
        $event = Invoke-RestMethod `
            -Method Post `
            -Uri "$BaseUrl/api/v1/events/$eventType" `
            -Headers @{ "Idempotency-Key" = "backlog-$runId-$index" } `
            -ContentType "application/json" `
            -Body $eventBody
        if ([int]$event.deliveryCount -ne 1) {
            throw "Expected one delivery for event $index, but found $($event.deliveryCount)"
        }
    }
    $ingest.Stop()

    $peakDatabaseClaimable = 0
    $peakDatabaseLeased = 0
    $peakPrometheusClaimable = 0.0
    $peakPrometheusLeased = 0.0
    $drained = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        $state = Get-DeliveryState -EventType $eventType
        $peakDatabaseClaimable = [Math]::Max($peakDatabaseClaimable, $state.Claimable)
        $peakDatabaseLeased = [Math]::Max($peakDatabaseLeased, $state.Leased)

        $prometheusClaimable = Invoke-PrometheusScalar `
            -Query 'max(hookrelay_delivery_queue_depth{state="claimable"}) or vector(0)'
        $prometheusLeased = Invoke-PrometheusScalar `
            -Query 'max(hookrelay_delivery_queue_depth{state="leased"}) or vector(0)'
        $peakPrometheusClaimable = [Math]::Max($peakPrometheusClaimable, $prometheusClaimable)
        $peakPrometheusLeased = [Math]::Max($peakPrometheusLeased, $prometheusLeased)

        if ($state.TerminalFailure -ne 0 -or $state.Stalled -ne 0) {
            throw "Unexpected delivery state: failed=$($state.TerminalFailure), stalled=$($state.Stalled)"
        }
        if ($state.Total -eq $EventCount -and $state.Succeeded -eq $EventCount) {
            $drained = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    $measurement.Stop()

    if (-not $drained) {
        throw "Delivery queue did not drain within 120 seconds"
    }
    if ($peakDatabaseClaimable -le 0 -or $peakPrometheusClaimable -le 0) {
        throw "Claimable backlog was not observed in both PostgreSQL and Prometheus"
    }
    if ($peakDatabaseLeased -le 0 -or $peakPrometheusLeased -le 0) {
        throw "Leased deliveries were not observed in both PostgreSQL and Prometheus"
    }

    $finalCounts = Invoke-DatabaseScalar -Query @"
SELECT concat_ws('|',
    (SELECT count(*) FROM webhook_events WHERE event_type = '$eventType'),
    (SELECT count(*)
       FROM webhook_deliveries d
       JOIN webhook_events e ON e.id = d.event_id
      WHERE e.event_type = '$eventType'),
    (SELECT count(*)
       FROM webhook_delivery_attempts a
       JOIN webhook_deliveries d ON d.id = a.delivery_id
       JOIN webhook_events e ON e.id = d.event_id
      WHERE e.event_type = '$eventType')
);
"@
    $finalValues = $finalCounts -split '\|'
    if ($finalValues.Count -ne 3) {
        throw "Expected three final count values, but found $($finalValues.Count)"
    }
    if ([int]$finalValues[0] -ne $EventCount `
            -or [int]$finalValues[1] -ne $EventCount `
            -or [int]$finalValues[2] -ne $EventCount) {
        throw "Final count mismatch: events=$($finalValues[0]), deliveries=$($finalValues[1]), attempts=$($finalValues[2])"
    }

    $claimCountAfter = Invoke-PrometheusScalar `
        -Query 'sum(hookrelay_delivery_claim_seconds_count{outcome="succeeded"}) or vector(0)'
    $claimSecondsAfter = Invoke-PrometheusScalar `
        -Query 'sum(hookrelay_delivery_claim_seconds_sum{outcome="succeeded"}) or vector(0)'
    $claimCountDelta = $claimCountAfter - $claimCountBefore
    $claimSecondsDelta = $claimSecondsAfter - $claimSecondsBefore
    if ($claimCountDelta -le 0 -or $claimSecondsDelta -lt 0) {
        throw "Claim timer did not increase during the measurement"
    }
    $averageClaimMilliseconds = ($claimSecondsDelta / $claimCountDelta) * 1000

    Write-Host ""
    Write-Host "Backlog observability demo completed"
    Write-Host "subscriptionId=$($subscription.id)"
    Write-Host "eventType=$eventType"
    Write-Host "eventCount=$EventCount"
    Write-Host "ingestSeconds=$([Math]::Round($ingest.Elapsed.TotalSeconds, 2))"
    Write-Host "drainSeconds=$([Math]::Round($measurement.Elapsed.TotalSeconds, 2))"
    Write-Host "peakDatabaseClaimable=$peakDatabaseClaimable"
    Write-Host "peakDatabaseLeased=$peakDatabaseLeased"
    Write-Host "peakPrometheusClaimable=$peakPrometheusClaimable"
    Write-Host "peakPrometheusLeased=$peakPrometheusLeased"
    Write-Host "claimQueries=$([Math]::Round($claimCountDelta, 0))"
    Write-Host "averageClaimMilliseconds=$([Math]::Round($averageClaimMilliseconds, 3))"
    Write-Host "finalCounts=events:$($finalValues[0]),deliveries:$($finalValues[1]),attempts:$($finalValues[2])"
} finally {
    Pop-Location
}
