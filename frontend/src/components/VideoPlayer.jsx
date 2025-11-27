import React from 'react'

const VideoPlayer = ({ video, onBack, serverIp, serverPort }) => {
    const videoUrl = `http://${serverIp}:${serverPort}/content/videos/${video.filename}`;

    return (
        <div className="video-player">
            <button className="back-button" onClick={onBack}>
                ← Back to Gallery
            </button>
            
            <div className="player-container">
                <h2>{video.filename}</h2>
                <video 
                    controls 
                    autoPlay 
                    className="main-video"
                    src={videoUrl}
                />
                
                <div className="video-details">
                    <p><strong>ID:</strong> {video.id}</p>
                    <p><strong>Size:</strong> {(video.size / 1024 / 1024).toFixed(2)} MB</p>
                    <p><strong>Uploaded:</strong> {new Date(video.upload_time).toLocaleString()}</p>
                    {video.client_id && <p><strong>Uploaded by:</strong> {video.client_id}</p>}
                </div>
            </div>
        </div>
    )
}

export default VideoPlayer