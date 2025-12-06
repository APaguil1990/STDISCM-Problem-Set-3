import React from 'react'

const VideoGrid = ({ videos, onVideoSelect, serverIp, serverPort }) => {
    // Deduplication - highlight duplicates
    const findDuplicates = (videoList) => {
        const seen = new Set();
        const duplicates = new Set();
        videoList.forEach(video => {
            const baseName = video.filename.replace(/\(\d+\)\.\w+$/, '.mp4');
            if (seen.has(baseName)) {
                duplicates.add(baseName);
            } else {
                seen.add(baseName);
            }
        });
        return duplicates;
    };

    const duplicates = findDuplicates(videos);

    return (
        <div className="video-grid">
            <h2>Uploaded Videos ({videos.length})</h2>
            {videos.length === 0 ? (
                <div className="no-videos">
                    <p>No videos uploaded yet. Use the ProducerClient to upload videos.</p>
                </div>
            ) : (
                <div className="grid">
                    {videos.map(video => (
                        <VideoCard
                            key={video.id}
                            video={video}
                            onSelect={onVideoSelect}
                            isDuplicate={duplicates.has(video.filename.replace(/\(\d+\)\.\w+$/, '.mp4'))}
                            serverIp={serverIp}
                            serverPort={serverPort}
                        />
                    ))}
                </div>
            )}
        </div>
    )
}

const VideoCard = ({ video, onSelect, isDuplicate, serverIp, serverPort }) => {
    const [isHovered, setIsHovered] = React.useState(false);
    const [previewError, setPreviewError] = React.useState(false);

    const handleMouseEnter = () => {
        setIsHovered(true);
        setPreviewError(false);
    };

    const handleMouseLeave = () => {
        setIsHovered(false);
    };

    // Construct URLs for video content
    const previewFilename = video.filename.replace('.mp4', '_preview.mp4');
    const previewUrl = `http://${serverIp}:${serverPort}/content/previews/${previewFilename}`;

    const handlePreviewError = () => {
        setPreviewError(true);
    };

    // Calculate savings percentage
    const savings = video.compressed_size > 0
        ? ((1 - (video.compressed_size / video.size)) * 100).toFixed(0)
        : 0;

    return (
        <div
            className={`video-card ${isDuplicate ? 'duplicate' : ''}`}
            onMouseEnter={handleMouseEnter}
            onMouseLeave={handleMouseLeave}
            onClick={() => onSelect(video)}
        >
            {isDuplicate && <div className="duplicate-badge">DUPLICATE</div>}

            {/* Show Savings Badge if compressed */}
            {video.compressed_size > 0 && (
                <div className="savings-badge">
                    -{savings}% Size
                </div>
            )}

            <div className="video-thumbnail">
                {isHovered && !previewError ? (
                    <div className="video-preview">
                        <video
                            src={previewUrl}
                            autoPlay
                            muted
                            loop
                            onError={handlePreviewError}
                        />
                        <div className="preview-label">10s Preview</div>
                    </div>
                ) : (
                    <div className="video-placeholder">
                        <span>📹</span>
                        <div className="placeholder-text">Hover to preview</div>
                    </div>
                )}
            </div>

            <div className="video-info">
                <h4 title={video.filename}>{video.filename}</h4>

                {/* NEW: Compression Stats Display */}
                <div className="size-info">
                    {video.compressed_size > 0 ? (
                        <div className="compression-row">
                            <span className="old-size">
                                {(video.size / 1024 / 1024).toFixed(2)} MB
                            </span>
                            <span className="arrow">➜</span>
                            <span className="new-size">
                                {(video.compressed_size / 1024 / 1024).toFixed(2)} MB
                            </span>
                        </div>
                    ) : (
                        <p className="normal-size">Size: {(video.size / 1024 / 1024).toFixed(2)} MB</p>
                    )}
                </div>

                <p className="meta-text">Uploaded: {new Date(video.upload_time).toLocaleTimeString()}</p>
                {video.client_id && <p className="meta-text">Client: {video.client_id}</p>}
            </div>
        </div>
    );
};

export default VideoGrid;