import React from 'react' 

const VideoGrid = ({ videos, onVideoSelect }) => {
    return (
        <div className="video-grid">
            <h2>Uploaded Videos ({videos.length})</h2> 
            <div className="grid">
                {videos.map(video => (
                    <VideoCard 
                        key={video.id} 
                        video={video} 
                        onSelect={onVideoSelect}
                    />
                ))}
            </div>
        </div>
    )
}

const VideoCard = ({ video, onSelect }) => {
    const [isHovered, setIsHovered] = React.useState(false) 

    const handleMouseEnter = () => {
        setIsHovered(true) 
        // Placeholder for now, this starts 10-second preview
    } 

    const handleMouseLeave = () => {
        setIsHovered(false)
    } 

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
              src={`/api/videos/${video.id}/preview`}
              autoPlay
              muted
              loop
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
      </div>
    </div>
  )
}

export default VideoGrid