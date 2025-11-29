import React from 'react'

const VideoGrid = ({ videos, onVideoSelect, serverIp, serverPort }) => {
    // Deduplication - highlight duplicates
    const findDuplicates = (videoList) => {
        const seen = new Set();
        const duplicates = new Set();
        videoList.forEach(video => {
            if (seen.has(video.id)) {
                duplicates.add(video.id);
            } else {
                seen.add(video.id);
            }
        });
        return duplicates;
    };

    const duplicates = findDuplicates(videos);

    return (
        <div className="video-grid">
            <h2>Uploaded Videos ({videos.length})</h2>
            <div className="grid">
                {videos.map(video => (
                    <VideoCard 
                        key={video.id} 
                        video={video} 
                        onSelect={onVideoSelect}
                        isDuplicate={duplicates.has(video.id)}
                        serverIp={serverIp}
                        serverPort={serverPort}
                    />
                ))}
            </div>
        </div>
    )
}

const VideoCard = ({ video, onSelect, serverIp, serverPort }) => {
    const [isHovered, setIsHovered] = React.useState(false);

    const handleMouseEnter = () => {
        setIsHovered(true);
    };

    const handleMouseLeave = () => {
        setIsHovered(false);
    };

    // Construct URLs for video content
    const videoUrl = `http://${serverIp}:${serverPort}/content/${video.filename}`;
    const previewUrl = `http://${serverIp}:${serverPort}/content/${video.filename.replace('.mp4', '_preview.mp4')}`;

    return (
        <div 
            className="video-card"
            onMouseEnter={handleMouseEnter}
            onMouseLeave={handleMouseLeave}
            onClick={() => onSelect(video)}
        >
            <div className="video-thumbnail">
                {isHovered ? (
                    <div className="video-preview">
                        <video 
                            src={previewUrl}
                            autoPlay
                            muted
                            loop
                            onError={(e) => {
                                console.log('Preview not available:', previewUrl);
                                // Fallback to placeholder if preview doesn't exist
                            }}
                        />
                        <div className="preview-label">10s Preview</div>
                    </div>
                ) : (
                    <div className="video-placeholder">
                        <span>📹</span>
                    </div>
                )}
            </div>
            <div className="video-info">
                <h4>{video.filename}</h4>
                <p>Size: {(video.size / 1024 / 1024).toFixed(2)} MB</p>
                <p>Uploaded: {new Date(video.upload_time).toLocaleString()}</p>
                {video.client_id && <p>Client: {video.client_id}</p>}
            </div>
        </div>
    );
};

export default VideoGrid;