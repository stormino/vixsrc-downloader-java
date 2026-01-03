#!/bin/bash

echo "=================================================="
echo "VixSrc Downloader - Quick Start"
echo "=================================================="
echo ""

# Check if .env exists
if [ ! -f .env ]; then
    echo "Creating .env file from example..."
    cp .env.example .env
    echo "✓ .env file created"
    echo ""
    echo "⚠️  IMPORTANT: Edit .env and add your TMDB_API_KEY"
    echo "   Get your free API key at: https://www.themoviedb.org/settings/api"
    echo ""
    read -p "Press Enter after you've added your TMDB_API_KEY to .env..."
fi

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    echo "✗ Docker not found. Please install Docker first."
    exit 1
fi

if ! command -v docker compose &> /dev/null; then
    echo "✗ Docker Compose not found. Please install Docker Compose first."
    exit 1
fi

echo "✓ Docker and Docker Compose found"
echo ""

# Build and start
echo "Building and starting application..."
docker compose up -d --build

echo ""
echo "=================================================="
echo "✓ Application started successfully!"
echo "=================================================="
echo ""
echo "Web UI: http://localhost:8080"
echo "API:    http://localhost:8080/api"
echo ""
echo "Views:"
echo "  - Search:    http://localhost:8080/"
echo "  - Downloads: http://localhost:8080/downloads"
echo "  - Settings:  http://localhost:8080/settings"
echo ""
echo "Logs: docker compose logs -f"
echo "Stop: docker compose down"
echo ""
