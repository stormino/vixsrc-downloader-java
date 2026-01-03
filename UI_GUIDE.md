# Vaadin UI - Development Guide

## UI Structure

```
Views (Routes)
├── SearchView (/)           - TMDB search with result cards
├── DownloadQueueView        - Real-time download queue with SSE
│   (/downloads)
└── SettingsView             - Configuration display
    (/settings)

Components
└── SearchResultCard         - Movie/TV search result with download dialog

Layout
└── MainLayout              - App shell with sidebar navigation
```

## Features Implemented

### SearchView ✅
- **TMDB Search** - Movies and TV shows
- **Content Type Filter** - Movies, TV Shows, or Both
- **Global Settings** - Default languages and quality
- **Result Cards** - Grid layout with metadata
- **Download Dialog** - Per-item language/quality selection
  - Movies: Direct download
  - TV: Season/Episode selectors

### DownloadQueueView ✅
- **Real-time Updates** - SSE subscription for live progress
- **Grid Display** - All download tasks
- **Columns:**
  - Title (with metadata-based names)
  - Status (color-coded badges)
  - Progress (bar with percentage)
  - Speed (bitrate when available)
  - Created time
  - Actions (cancel/info)
- **Auto-refresh** - Updates from SSE stream
- **Manual refresh** - Refresh button

### SettingsView ✅
- **TMDB Configuration** - API key display
- **Download Settings** - All config values (read-only)
- **Extractor Settings** - Base URL, timeout, retries
- **System Info** - Java version, OS, CPU count
- **Tool Check** - Verify yt-dlp/ffmpeg availability

### Components ✅
- **SearchResultCard** - Responsive card with:
  - Title and year
  - Rating (stars)
  - TV-specific: seasons/episodes count
  - Overview (truncated)
  - Download button → Dialog

### MainLayout ✅
- **Sidebar Navigation** - Collapsible drawer
- **Header** - App title + version
- **Routes** - Clean URLs

## Running the UI

### Development Mode
```bash
# Run with live reload
mvn spring-boot:run

# Access UI
http://localhost:8080
```

### Production Build
```bash
# Build optimized bundle
mvn clean package -Pproduction

# Run
java -jar target/vixsrc-downloader-1.0.0.jar
```

## Real-time Progress (SSE)

The DownloadQueueView subscribes to SSE endpoint on mount:

```java
@Override
protected void onAttach(AttachEvent attachEvent) {
    sseSubscription = WebClient.create()
        .get()
        .uri("http://localhost:8080/api/progress/stream")
        .retrieve()
        .bodyToFlux(ProgressUpdate.class)
        .subscribe(
            update -> ui.access(() -> handleProgressUpdate(update))
        );
}
```

**Important:** SSE updates are pushed from backend whenever:
- Download status changes
- Progress percentage updates
- Bitrate/speed changes
- Errors occur

## Styling

Custom theme: `frontend/themes/vixsrc/`
- `theme.json` - Theme configuration
- `styles/theme.css` - Custom CSS

**Badge System:**
- Success (green) - Completed
- Error (red) - Failed/Cancelled
- Contrast (gray) - Downloading
- Primary (blue) - Queued/Extracting

## User Workflow

1. **Search** → Navigate to root `/`
   - Enter movie/TV show name
   - Select content type
   - Set default languages/quality
   - Click search

2. **Select** → Browse results
   - View metadata (title, year, rating, overview)
   - Click "Download" on desired item

3. **Configure** → Download dialog
   - Adjust languages (multi-select)
   - Select quality (best/1080/720/worst)
   - For TV: Choose season/episode
   - Click "Add to Queue"

4. **Monitor** → Navigate to `/downloads`
   - View all downloads
   - Real-time progress updates
   - Cancel active downloads
   - View error details for failed downloads

5. **Settings** → Navigate to `/settings`
   - View configuration
   - Check TMDB API key status
   - Verify yt-dlp/ffmpeg availability

## Customization

### Change Default Languages
Edit `SearchView.java`:
```java
languageSelector.setItems("en", "it", "es", "fr", "de", "pt", "ja", "ko");
languageSelector.setValue(Set.of("en")); // Change default
```

### Change Grid Columns
Edit `DownloadQueueView.java` - add/remove grid columns

### Change Theme Colors
Edit `frontend/themes/vixsrc/styles/theme.css`:
```css
:root {
    --lumo-primary-color: hsl(214, 90%, 52%); /* Change primary color */
}
```

## Troubleshooting

### SSE Connection Issues
- Check backend is running on port 8080
- Verify `/api/progress/stream` endpoint accessible
- Check browser console for errors
- WebFlux dependency must be present

### Search Not Working
- Verify TMDB_API_KEY is set
- Check backend logs for API errors
- Ensure `TmdbMetadataService` is initialized

### UI Not Loading
- Run `mvn clean` to clear caches
- Delete `node_modules/` and `frontend/generated/`
- Run `mvn vaadin:prepare-frontend`

### Progress Not Updating
- Check SSE subscription in browser DevTools (Network tab)
- Verify `ProgressBroadcastService` is broadcasting
- Check `DownloadExecutorService` is calling progress callback

## Browser Compatibility

Tested on:
- Chrome/Edge 120+
- Firefox 120+
- Safari 17+

## Performance

- **SSE Connection** - One per browser tab
- **Grid Rendering** - Virtualized (handles 1000+ items)
- **Search Results** - CSS Grid (responsive)
- **Bundle Size** - ~2MB production (Vaadin + components)

## Next Steps

Potential enhancements:
- [ ] Bulk TV download UI (entire seasons)
- [ ] Download queue persistence view
- [ ] Custom download directory per task
- [ ] Subtitle language selection
- [ ] Download history view
- [ ] Statistics dashboard
- [ ] Dark mode toggle
