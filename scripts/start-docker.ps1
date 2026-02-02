# PowerShell script to start the application in Docker
# 
# Usage:
#   .\scripts\start-docker.ps1                    # Start on port 8080
#   .\scripts\start-docker.ps1 -Port 8081        # Start on custom port
#   .\scripts\start-docker.ps1 -Port 8081 -Build # Start with image rebuild
#   .\scripts\start-docker.ps1 -Stop             # Stop containers
#
# The script automatically:
#   - Checks Docker availability
#   - Checks and starts Ollama (if not running)
#   - Checks that the port is free
#   - Starts containers
#   - Waits for application readiness and checks health check
#   - Shows status of all components

param(
    [int]$Port = 8080,
    [switch]$Build = $false,
    [switch]$Stop = $false
)

# Set UTF-8 encoding for output
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
    $PSDefaultParameterValues['*:Encoding'] = 'utf8'
    chcp 65001 | Out-Null
} catch {
    # If UTF-8 setup fails, continue anyway
}

$ErrorActionPreference = "Stop"

# Output colors
function Write-Info {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Green
}

function Write-Warning {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Yellow
}

function Write-Error {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Red
}

# Automatically navigate to project root directory
$currentDir = Get-Location
$projectRoot = $currentDir.Path
$maxDepth = 5
$depth = 0

# Find project root directory (where docker-compose.yml is located)
while ($depth -lt $maxDepth -and -not (Test-Path (Join-Path $projectRoot "docker-compose.yml"))) {
    $parent = Split-Path -Parent $projectRoot
    if ($parent -eq $projectRoot) {
        # Reached filesystem root
        break
    }
    $projectRoot = $parent
    $depth++
}

if (-not (Test-Path (Join-Path $projectRoot "docker-compose.yml"))) {
    Write-Error "Error: docker-compose.yml not found. Make sure you are in the project directory."
    exit 1
}

# Navigate to project root directory
if ($currentDir.Path -ne $projectRoot) {
    Write-Info "Navigating to project root directory: $projectRoot"
    Set-Location $projectRoot
}

# If stop requested
if ($Stop) {
    Write-Info "Stopping containers..."
    docker-compose down
    Write-Success "Containers stopped"
    exit 0
}

Write-Info "Starting HH Assistant in Docker"
Write-Info "Application port: $Port"
Write-Host ""

# Check Docker
Write-Info "Checking Docker..."
try {
    $dockerVersion = docker --version 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Docker not found"
    }
    Write-Success "Docker installed: $dockerVersion"
} catch {
    Write-Error "Docker is not installed or not running. Please install Docker Desktop."
    exit 1
}

# Check that Docker Desktop is running
try {
    docker ps | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker not running"
    }
} catch {
    Write-Error "Docker Desktop is not running. Please start Docker Desktop and try again."
    exit 1
}

# Check Ollama
Write-Info "Checking Ollama..."
$ollamaRunning = $false
try {
    $response = Invoke-WebRequest -Uri "http://localhost:11434/api/tags" -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
    if ($response.StatusCode -eq 200) {
        $ollamaRunning = $true
        Write-Success "Ollama is running on http://localhost:11434"
    }
} catch {
    Write-Warning "Ollama is not responding on http://localhost:11434"
    Write-Warning "Attempting to start Ollama..."
    
    # Try to start Ollama via command
    try {
        $ollamaProcess = Start-Process -FilePath "ollama" -ArgumentList "serve" -NoNewWindow -PassThru -ErrorAction Stop
        Start-Sleep -Seconds 3
        
        # Check again
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:11434/api/tags" -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
            if ($response.StatusCode -eq 200) {
                $ollamaRunning = $true
                Write-Success "Ollama started"
            }
        } catch {
            Write-Warning "Failed to start Ollama automatically"
            Write-Warning "Make sure Ollama is installed and start it manually: ollama serve"
        }
    } catch {
        Write-Warning "Command 'ollama' not found"
        Write-Warning "Install Ollama from https://ollama.ai/download"
        Write-Warning "Or start Ollama manually before starting the application"
    }
}

if (-not $ollamaRunning) {
    Write-Warning "WARNING: Ollama is not running!"
    Write-Warning "The application may not work correctly without Ollama."
    $continue = Read-Host "Continue startup? (y/n)"
    if ($continue -ne "y" -and $continue -ne "Y") {
        Write-Info "Startup cancelled by user"
        exit 1
    }
}

# Check if port is in use
Write-Info "Checking port $Port..."
$portInUse = $false

# Quick check via Get-NetTCPConnection
try {
    $existingConnection = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
    if ($existingConnection) {
        $portInUse = $true
    }
} catch {
    # Ignore error
}

# If Get-NetTCPConnection didn't work, try Test-NetConnection
if (-not $portInUse) {
    try {
        $connection = Test-NetConnection -ComputerName localhost -Port $Port -WarningAction SilentlyContinue -InformationLevel Quiet -ErrorAction Stop
        if ($connection) {
            $portInUse = $true
        }
    } catch {
        # Port is free or check failed (continue)
    }
}

if ($portInUse) {
    Write-Warning "Port $Port is already in use!"
    Write-Warning "Use a different port: .\scripts\start-docker.ps1 -Port 8081"
    exit 1
}

Write-Success "Port $Port is free"

# Set environment variable for port
$env:APP_PORT = $Port.ToString()
$env:APP_API_BASE_URL = "http://localhost:$Port"

Write-Host ""
Write-Info "Starting Docker Compose..."

# Build image if needed
if ($Build) {
    Write-Info "Building application image..."
    docker-compose build app
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Error building image"
        exit 1
    }
}

# Start containers
Write-Info "Starting containers..."
docker-compose up -d

if ($LASTEXITCODE -ne 0) {
    Write-Error "Error starting containers"
    exit 1
}

Write-Success "Containers started"
Write-Host ""

# Wait for application startup
Write-Info "Waiting for application startup (this may take 30-60 seconds)..."
$maxAttempts = 30
$attempt = 0
$appReady = $false

while ($attempt -lt $maxAttempts -and -not $appReady) {
    Start-Sleep -Seconds 2
    $attempt++
    
    try {
        $healthResponse = Invoke-WebRequest -Uri "http://localhost:$Port/actuator/health" -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
        if ($healthResponse.StatusCode -eq 200) {
            $healthData = $healthResponse.Content | ConvertFrom-Json
            if ($healthData.status -eq "UP") {
                $appReady = $true
                Write-Success "Application started and ready!"
                Write-Host ""
                Write-Success "Application available at: http://localhost:$Port"
                Write-Success "Health check: http://localhost:$Port/actuator/health"
                Write-Success "Logs: docker-compose logs -f app"
                Write-Success "Stop: docker-compose down"
                Write-Host ""
                
                # Show component status
                Write-Info "Component status:"
                $components = $healthData.components
                foreach ($component in $components.PSObject.Properties) {
                    $status = $component.Value.status
                    $statusIcon = if ($status -eq "UP") { "[OK]" } else { "[FAIL]" }
                    Write-Host "   $statusIcon $($component.Name): $status"
                }
                
                exit 0
            }
        }
    } catch {
        # Continue waiting
        Write-Host "." -NoNewline
    }
}

Write-Host ""
Write-Warning "Application did not respond to health check within $($maxAttempts * 2) seconds"
Write-Info "Check logs: docker-compose logs app"
Write-Info "Check status: docker-compose ps"

# Show last log lines
Write-Host ""
Write-Info "Last log lines:"
docker-compose logs --tail=20 app

exit 1