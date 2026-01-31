# VixSrc Video Downloader - Java/Spring Boot + Vaadin

A production-ready video downloader for vixsrc.to with web UI, built with Spring Boot and Vaadin.

## Features

- **Modern Web UI** - Vaadin-based interface with real-time updates
  - Search movies and TV shows (TMDB integration)
  - Download queue with live progress bars
  - Settings and configuration view
- **REST API** - Complete API for automation
- **TMDB Integration** - Search movies and TV shows, auto-generate filenames
- **Cloudflare Bypass** - Automatic handling of Cloudflare protection
- **Parallel Downloads** - Configurable concurrent download workers
- **Real-time Progress** - Server-Sent Events (SSE) for live download progress
- **Multi-language Support** - Download with multiple audio tracks (per-download selection)
- **Smart File Organization** - Automatic directory structure (Show/Season/Episode)
- **Production Ready** - Docker support, proper error handling, logging

## Requirements

- Java 21+
- Maven 3.6+
- ffmpeg (for video downloading)
- Docker (optional, for containerized deployment)

## Quick Start

### Docker (Recommended)

```bash
# 1. Clone repository
git clone <repo>
cd vixsrc-downloader-java

# 2. Create .env file
cp .env.example .env
# Edit .env and add your TMDB_API_KEY

# 3. Run with docker-compose
docker-compose up -d

# 4. Access Web UI
open http://localhost:8080

# 5. Access API
curl http://localhost:8080/api/downloads
```

### Manual Setup

```bash
# 1. Install ffmpeg
sudo apt-get install ffmpeg  # Debian/Ubuntu
brew install ffmpeg          # macOS

# 2. Set TMDB API key
export TMDB_API_KEY="your_api_key_here"

# 3. Build and run
mvn clean package -DskipTests
java -jar target/vixsrc-downloader-1.0.0.jar
```

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `TMDB_API_KEY` | TMDB API key for metadata | (required) |
| `DOWNLOAD_BASE_PATH` | Download directory | `/downloads` |
| `DOWNLOAD_TEMP_PATH` | Temporary files directory | `/downloads/temp` |
| `PARALLEL_DOWNLOADS` | Max concurrent downloads | `3` |
| `SEGMENT_CONCURRENCY` | HLS segment download concurrency | `5` |
| `DEFAULT_QUALITY` | Default quality (best/720/1080) | `best` |
| `DEFAULT_LANGUAGE` | Default language | `en` |
| `SERVER_PORT` | Web server port | `8080` |
| `LOG_LEVEL` | Logging level | `INFO` |

### application.yml

See `src/main/resources/application.yml` for all configuration options.

## Architecture

### Backend (Spring Boot)

```
├── Controllers (REST API + SSE)
│   ├── DownloadController - Movie/TV download management
│   └── ProgressController - Real-time progress updates (SSE)
├── Services
│   ├── VixSrcExtractorService - Cloudflare bypass + URL extraction
│   ├── DownloadExecutorService - ffmpeg wrapper
│   ├── TmdbMetadataService - TMDB API integration
│   ├── DownloadQueueService - Download orchestration
│   └── ProgressBroadcastService - SSE broadcasting
├── Models
│   ├── DownloadTask - Download task entity
│   ├── DownloadStatus - Task status enum
│   ├── ProgressUpdate - Progress event
│   └── ContentMetadata - TMDB metadata
└── Config
    ├── HttpClientConfig - OkHttp with Cloudflare handling
    ├── ExecutorConfig - Thread pool configuration
    └── TmdbConfig - TMDB client setup
```

### Frontend (Vaadin)

```
├── Views
│   ├── SearchView - TMDB search interface (/)
│   ├── DownloadQueueView - Queue with real-time progress (/downloads)
│   └── SettingsView - Configuration display (/settings)
├── Components
│   └── SearchResultCard - Movie/TV search result card
└── Layout
    └── MainLayout - App shell with sidebar navigation
```

## API Endpoints

### Web UI

The application provides a modern web interface accessible at `http://localhost:8080`

**Views:**
- `/` - Search movies and TV shows
- `/downloads` - View download queue with real-time progress
- `/settings` - View configuration and system info

**Features:**
- Real-time progress updates via SSE
- TMDB search with metadata
- Per-download language and quality selection
- Color-coded status badges
- Responsive grid layout

See [UI_GUIDE.md](UI_GUIDE.md) for detailed UI documentation.

### REST API

### Search

```http
GET /api/search/movies?query=fight+club
GET /api/search/tv?query=breaking+bad
```

### Downloads

```http
# Add movie download
POST /api/download/movie
  ?tmdbId=550
  &languages=en,it
  &quality=1080

# Add TV episode download
POST /api/download/tv
  ?tmdbId=60625
  &season=4
  &episode=4
  &languages=en
  &quality=best

# Get all downloads
GET /api/downloads

# Get specific download
GET /api/downloads/{id}

# Cancel download
DELETE /api/downloads/{id}
```

### Real-time Progress

```http
# SSE endpoint (auto-reconnects)
GET /api/progress/stream
```

## Development

### Build

```bash
# Build JAR
mvn clean package

# Build without tests
mvn clean package -DskipTests

# Build for production (Vaadin optimization)
mvn clean package -Pproduction
```

### Run Locally

```bash
# Run with Maven
mvn spring-boot:run

# Run JAR
java -jar target/vixsrc-downloader-1.0.0.jar

# Run with specific profile
java -jar target/vixsrc-downloader-1.0.0.jar --spring.profiles.active=dev
```

### Testing

```bash
# Unit tests
mvn test

# Integration tests
mvn verify
```

## How It Works

### Download Flow

1. **Search** - User searches TMDB for content
2. **Add to Queue** - User selects content + languages
3. **Extract URL** - System extracts HLS playlist from vixsrc.to
   - Multiple extraction strategies (window.masterPlaylist, regex, API endpoints)
   - Cloudflare bypass via custom OkHttp interceptor
4. **Download** - ffmpeg downloads video
   - Real-time progress parsed from stdout
   - Progress broadcast via SSE to UI
5. **Save** - File saved with metadata-based filename
   - Movies: `Title.Year.mp4`
   - TV: `Show.S01E01.Episode.mp4`
   - Directory structure: `Show/Season XX/episode.mp4`

### Multi-Language Support

For downloads with multiple languages:
1. Download primary language (video + audio)
2. Download additional audio tracks separately
3. Merge all tracks with ffmpeg
4. Set language metadata for each audio track

## File Organization

```
/downloads/
├── Fight.Club.1999.mp4
├── Breaking.Bad/
│   ├── Season 01/
│   │   ├── Breaking.Bad.S01E01.Pilot.mp4
│   │   └── Breaking.Bad.S01E02.Cat's.in.the.Bag.mp4
│   └── Season 04/
│       └── Breaking.Bad.S04E04.Ozymandias.mp4
└── /temp/
    └── (temporary download files)
```

## Troubleshooting

### "ffmpeg not found"

Install ffmpeg:
```bash
# Debian/Ubuntu
sudo apt-get install ffmpeg

# macOS
brew install ffmpeg
```

### "Failed to extract playlist URL"

- Content may not be available on vixsrc.to
- Cloudflare protection may have changed (check logs)
- Try different content

### Downloads are slow

- Reduce `SEGMENT_CONCURRENCY` (HLS segment downloads)
- Try lower quality: `quality=720`
- Check network connection

### TMDB search not working

- Verify `TMDB_API_KEY` is set correctly
- Get free API key at: https://www.themoviedb.org/settings/api

## Production Deployment

### Docker (Pre-built Image)

The easiest way to run the application is using the pre-built image from GitHub Container Registry:

```bash
docker run -d \
  --name vixsrc-downloader \
  -p 8080:8080 \
  -e TMDB_API_KEY="your_tmdb_api_key" \
  -v /path/to/downloads:/downloads \
  ghcr.io/stormino/vixsrc-downloader-java:latest
```

### Docker (Build Locally)

```bash
# Build image
docker build -t vixsrc-downloader .

# Run container
docker run -d \
  --name vixsrc-downloader \
  -p 8080:8080 \
  -e TMDB_API_KEY="your_tmdb_api_key" \
  -v /path/to/downloads:/downloads \
  vixsrc-downloader
```

### Docker Run - All Options

```bash
docker run -d \
  --name vixsrc-downloader \
  -p 8080:8080 \
  -e TMDB_API_KEY="your_tmdb_api_key" \
  -e PARALLEL_DOWNLOADS=3 \
  -e SEGMENT_CONCURRENCY=5 \
  -e DEFAULT_QUALITY=best \
  -e DEFAULT_LANGUAGE=en \
  -e LOG_LEVEL=INFO \
  -e JAVA_OPTS="-Xmx512m -Xms256m" \
  -v /path/to/downloads:/downloads \
  --restart unless-stopped \
  ghcr.io/stormino/vixsrc-downloader-java:latest
```

### Docker Compose

1. Create a `.env` file:
```bash
TMDB_API_KEY=your_tmdb_api_key
DOWNLOAD_PATH=/path/to/downloads
PARALLEL_DOWNLOADS=3
DEFAULT_QUALITY=best
LOG_LEVEL=INFO
```

2. Run with docker-compose:
```bash
# Start
docker-compose up -d

# View logs
docker-compose logs -f

# Stop
docker-compose down
```

### Docker Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `TMDB_API_KEY` | **Yes** | - | Your TMDB API key |
| `DOWNLOAD_BASE_PATH` | No | `/downloads` | Download directory inside container |
| `DOWNLOAD_TEMP_PATH` | No | `/downloads/temp` | Temp files directory |
| `PARALLEL_DOWNLOADS` | No | `3` | Max concurrent downloads |
| `SEGMENT_CONCURRENCY` | No | `5` | HLS segment download concurrency |
| `DEFAULT_QUALITY` | No | `best` | Default quality (best/720/1080/worst) |
| `DEFAULT_LANGUAGE` | No | `en` | Default language code |
| `LOG_LEVEL` | No | `INFO` | Logging level (DEBUG/INFO/WARN/ERROR) |
| `JAVA_OPTS` | No | `-Xmx512m -Xms256m` | JVM options |
| `SERVER_PORT` | No | `8080` | HTTP server port |

### Docker Volume Mounts

| Container Path | Description |
|----------------|-------------|
| `/downloads` | Downloaded videos are saved here. **Must be mounted** |

### Docker Image Tags

| Tag | Description |
|-----|-------------|
| `latest` | Latest stable release from main branch |
| `v1.0.0` | Specific version release |
| `main` | Latest build from main branch |

### Kubernetes (example)

See `k8s/` directory for deployment manifests (coming soon).

## Development Roadmap

### Phase 1 - Backend Core ✅
- [x] HTTP client with Cloudflare bypass
- [x] Playlist URL extraction
- [x] Download execution (ffmpeg)
- [x] TMDB integration
- [x] REST API
- [x] SSE progress broadcasting
- [x] Docker support

### Phase 2 - Vaadin UI ✅
- [x] Main layout and routing
- [x] Search view with TMDB integration
- [x] Download queue view with real-time progress
- [x] Settings view
- [x] Search result cards
- [x] Responsive design
- [x] Custom theme

### Phase 3 - Production Features (Future)
- [ ] Multi-language download implementation
- [ ] Bulk TV download UI (entire seasons)
- [ ] Download queue persistence (H2/PostgreSQL)
- [ ] Retry mechanisms
- [ ] Subtitle download support
- [ ] Webhook notifications
- [ ] API authentication
- [ ] Dark mode
- [ ] Download history view

## License

This project is for educational purposes only. Respect copyright laws and terms of service.

## Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Submit a pull request

## Support

For issues and questions:
- GitHub Issues: [link]
- Documentation: [link]
