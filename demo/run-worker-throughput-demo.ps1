param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [ValidateRange(100, 2000)]
    [int]$EventCount = 500,
    [ValidateRange(1, 100)]
    [int]$SeedVus = 50,
    [ValidateRange(1, 5)]
    [int]$Repetitions = 3
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$workerIntervalVariable = "HOOK_RELAY_WORKER_POLL_INTERVAL_MILLIS"
$receiverDelayVariable = "WEBHOOK_DELAY_MILLIS"
$originalWorkerInterval = [Environment]::GetEnvironmentVariable($workerIntervalVariable, "Process")
$originalReceiverDelay = [Environment]::GetEnvironmentVariable($receiverDelayVariable, "Process")
$results = @()

function Set-ProcessEnvironment {
    param(
        [Parameter(Mandatory)]
        [string]$Name,
        [AllowNull()]
        [string]$Value
    )

    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

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

function Assert-NoActiveDeliveries {
    $activeDeliveries = [int](Invoke-DatabaseScalar -Query @"
SELECT count(*)
FROM webhook_deliveries
WHERE status IN ('PENDING', 'RETRY_WAIT', 'PROCESSING');
"@)
    if ($activeDeliveries -ne 0) {
        throw "Expected no active deliveries before measurement, but found $activeDeliveries"
    }
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

function Get-MetricValue {
    param(
        [Parameter(Mandatory)]
        [string]$MetricName,
        [Parameter(Mandatory)]
        [string]$Outcome
    )

    $response = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/actuator/prometheus"
    $pattern = "(?m)^$([regex]::Escape($MetricName))\{[^}]*outcome=`"$([regex]::Escape($Outcome))`"[^}]*\}\s+([0-9.eE+-]+)$"
    $matches = [regex]::Matches($response.Content, $pattern)
    if ($matches.Count -eq 0) {
        return 0.0
    }
    $sum = 0.0
    foreach ($match in $matches) {
        $sum += [double]::Parse(
            $match.Groups[1].Value,
            [Globalization.CultureInfo]::InvariantCulture
        )
    }
    return $sum
}

function Wait-WorkerInitialPoll {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $count = Get-MetricValue `
            -MetricName "hookrelay_delivery_claim_seconds_count" `
            -Outcome "succeeded"
        if ($count -ge 1) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Worker initial poll was not observed within 30 seconds"
}

function Start-ApplicationWithInterval {
    param(
        [Parameter(Mandatory)]
        [int]$PollIntervalMillis
    )

    Set-ProcessEnvironment -Name $workerIntervalVariable -Value "$PollIntervalMillis"
    docker compose up --detach --force-recreate --no-deps app
    if ($LASTEXITCODE -ne 0) {
        throw "Application recreation failed"
    }
    Wait-ApplicationHealthy
}

function Get-RunCounts {
    param(
        [Parameter(Mandatory)]
        [string]$EventType
    )

    $query = @"
SELECT concat_ws('|',
    count(DISTINCT e.id),
    count(*),
    count(*) FILTER (WHERE d.status = 'PENDING'),
    count(*) FILTER (WHERE d.status = 'PROCESSING'),
    count(*) FILTER (WHERE d.status = 'SUCCEEDED'),
    count(*) FILTER (WHERE d.status IN ('RETRY_WAIT', 'FAILED', 'DEAD_LETTER')),
    coalesce(sum(d.attempt_count), 0)
)
FROM webhook_deliveries d
JOIN webhook_events e ON e.id = d.event_id
WHERE e.event_type = '$EventType';
"@
    $values = (Invoke-DatabaseScalar -Query $query) -split '\|'
    if ($values.Count -ne 7) {
        throw "Expected seven event and delivery count values, but found $($values.Count)"
    }
    return @{
        Events = [int]$values[0]
        Total = [int]$values[1]
        Pending = [int]$values[2]
        Processing = [int]$values[3]
        Succeeded = [int]$values[4]
        Failure = [int]$values[5]
        AttemptCount = [int]$values[6]
    }
}

function Wait-RunCompleted {
    param(
        [Parameter(Mandatory)]
        [string]$EventType
    )

    for ($attempt = 0; $attempt -lt 360; $attempt++) {
        $counts = Get-RunCounts -EventType $EventType
        if ($counts.Failure -ne 0) {
            throw "Unexpected delivery failure: $($counts.Failure)"
        }
        if ($counts.Events -eq $EventCount `
                -and $counts.Total -eq $EventCount `
                -and $counts.Succeeded -eq $EventCount `
                -and $counts.AttemptCount -eq $EventCount) {
            return $counts
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Worker throughput run did not finish within 180 seconds"
}

function Invoke-SeedLoad {
    param(
        [Parameter(Mandatory)]
        [string]$EventType,
        [Parameter(Mandatory)]
        [string]$RunId
    )

    $loadTestPath = Join-Path $repositoryRoot "load-tests"
    docker run --rm `
        --network hook-relay_default `
        --mount "type=bind,source=$loadTestPath,target=/scripts,readonly" `
        -e BASE_URL=http://app:8080 `
        -e EVENT_TYPE=$EventType `
        -e RUN_ID=$RunId `
        -e EVENT_COUNT=$EventCount `
        -e VUS=$SeedVus `
        grafana/k6:2.1.0 run /scripts/worker-throughput-seed.js
    if ($LASTEXITCODE -ne 0) {
        throw "Worker throughput seed load failed"
    }
}

Push-Location $repositoryRoot
try {
    Set-ProcessEnvironment -Name $receiverDelayVariable -Value "0"
    Set-ProcessEnvironment -Name $workerIntervalVariable -Value "3600000"

    docker compose stop app
    if ($LASTEXITCODE -ne 0) {
        throw "Application stop failed"
    }

    docker compose up --detach --wait postgres
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL startup failed"
    }

    docker compose up --detach --force-recreate --no-deps --wait webhook-receiver
    if ($LASTEXITCODE -ne 0) {
        throw "Webhook receiver startup failed"
    }

    Assert-NoActiveDeliveries
    docker compose up --build --detach --force-recreate --no-deps app
    if ($LASTEXITCODE -ne 0) {
        throw "Application startup failed"
    }
    Wait-ApplicationHealthy

    for ($runNumber = 1; $runNumber -le $Repetitions; $runNumber++) {
        if ($runNumber -gt 1) {
            Start-ApplicationWithInterval -PollIntervalMillis 3600000
        }
        Wait-WorkerInitialPoll
        Assert-NoActiveDeliveries

        $runId = [Guid]::NewGuid().ToString("N")
        $eventType = "load.worker.$runId"
        $subscriptionBody = @{
            name = "worker-throughput-$runId"
            endpointUrl = "http://webhook-receiver:8081/webhooks/delay"
            eventTypes = @($eventType)
        } | ConvertTo-Json -Compress
        $subscription = Invoke-RestMethod `
            -Method Post `
            -Uri "$BaseUrl/api/v1/subscriptions" `
            -ContentType "application/json" `
            -Body $subscriptionBody

        $seed = [Diagnostics.Stopwatch]::StartNew()
        Invoke-SeedLoad -EventType $eventType -RunId $runId
        $seed.Stop()

        $seededCounts = Get-RunCounts -EventType $eventType
        if ($seededCounts.Events -ne $EventCount `
                -or $seededCounts.Total -ne $EventCount `
                -or $seededCounts.Pending -ne $EventCount `
                -or $seededCounts.AttemptCount -ne 0) {
            throw "Seed count mismatch: events=$($seededCounts.Events), deliveries=$($seededCounts.Total), pending=$($seededCounts.Pending), attempts=$($seededCounts.AttemptCount)"
        }

        $wallClock = [Diagnostics.Stopwatch]::StartNew()
        Start-ApplicationWithInterval -PollIntervalMillis 1000
        $completedCounts = Wait-RunCompleted -EventType $eventType
        $wallClock.Stop()

        $timing = Invoke-DatabaseScalar -Query @"
SELECT concat_ws('|',
    round(extract(epoch FROM (max(a.finished_at) - min(a.started_at)))::numeric, 6),
    count(*)
)
FROM webhook_delivery_attempts a
JOIN webhook_deliveries d ON d.id = a.delivery_id
JOIN webhook_events e ON e.id = d.event_id
WHERE e.event_type = '$eventType';
"@
        $timingValues = $timing -split '\|'
        if ($timingValues.Count -ne 2 -or [int]$timingValues[1] -ne $EventCount) {
            throw "Attempt timing count mismatch: $timing"
        }
        $processingSeconds = [double]::Parse(
            $timingValues[0],
            [Globalization.CultureInfo]::InvariantCulture
        )
        if ($processingSeconds -le 0) {
            throw "Processing duration must be positive"
        }

        $receiverLogs = docker compose logs --no-log-prefix webhook-receiver
        if ($LASTEXITCODE -ne 0) {
            throw "Webhook receiver log query failed"
        }
        $receiverRequestCount = @($receiverLogs | Select-String -SimpleMatch $runId).Count
        if ($receiverRequestCount -ne $EventCount) {
            throw "Receiver request count mismatch: expected=$EventCount actual=$receiverRequestCount"
        }

        $processingCount = Get-MetricValue `
            -MetricName "hookrelay_delivery_processing_seconds_count" `
            -Outcome "succeeded"
        $processingSum = Get-MetricValue `
            -MetricName "hookrelay_delivery_processing_seconds_sum" `
            -Outcome "succeeded"
        if ([int]$processingCount -ne $EventCount) {
            throw "Processing metric count mismatch: expected=$EventCount actual=$processingCount"
        }

        $throughput = $EventCount / $processingSeconds
        $averageDeliveryMilliseconds = ($processingSum / $processingCount) * 1000
        $result = [pscustomobject]@{
            Run = $runNumber
            SubscriptionId = $subscription.id
            EventType = $eventType
            SeedSeconds = [Math]::Round($seed.Elapsed.TotalSeconds, 2)
            WallSeconds = [Math]::Round($wallClock.Elapsed.TotalSeconds, 2)
            ProcessingSeconds = [Math]::Round($processingSeconds, 3)
            Throughput = [Math]::Round($throughput, 2)
            AverageDeliveryMilliseconds = [Math]::Round($averageDeliveryMilliseconds, 3)
            ReceiverRequests = $receiverRequestCount
            Succeeded = $completedCounts.Succeeded
        }
        $results += $result

        Write-Host ""
        Write-Host "Worker throughput run $runNumber completed"
        Write-Host "eventType=$($result.EventType)"
        Write-Host "seedSeconds=$($result.SeedSeconds)"
        Write-Host "wallSeconds=$($result.WallSeconds)"
        Write-Host "processingSeconds=$($result.ProcessingSeconds)"
        Write-Host "throughputPerSecond=$($result.Throughput)"
        Write-Host "averageDeliveryMilliseconds=$($result.AverageDeliveryMilliseconds)"
        Write-Host "finalCounts=events:$EventCount,deliveries:$($result.Succeeded),attempts:$EventCount,receiverRequests:$($result.ReceiverRequests)"
    }
} finally {
    Set-ProcessEnvironment -Name $workerIntervalVariable -Value $originalWorkerInterval
    Set-ProcessEnvironment -Name $receiverDelayVariable -Value $originalReceiverDelay
    docker compose up --detach --force-recreate --no-deps --wait webhook-receiver | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Failed to restore the default webhook receiver settings"
    }
    docker compose up --detach --force-recreate --no-deps --wait app | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Failed to restore the default application settings"
    }
    Pop-Location
}

if ($results.Count -eq $Repetitions) {
    $throughputs = @($results | ForEach-Object { $_.Throughput } | Sort-Object)
    $averageThroughput = ($throughputs | Measure-Object -Average).Average
    $medianThroughput = if ($throughputs.Count % 2 -eq 1) {
        $throughputs[[Math]::Floor($throughputs.Count / 2)]
    } else {
        ($throughputs[$throughputs.Count / 2 - 1] + $throughputs[$throughputs.Count / 2]) / 2
    }

    Write-Host ""
    Write-Host "Worker throughput measurement completed"
    Write-Host "runs=$Repetitions"
    Write-Host "eventsPerRun=$EventCount"
    Write-Host "averageThroughputPerSecond=$([Math]::Round($averageThroughput, 2))"
    Write-Host "medianThroughputPerSecond=$([Math]::Round($medianThroughput, 2))"
    Write-Host "minimumThroughputPerSecond=$($throughputs[0])"
    Write-Host "maximumThroughputPerSecond=$($throughputs[-1])"
}
