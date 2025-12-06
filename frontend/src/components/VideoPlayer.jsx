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
                <div className="video-wrapper">
                    <video 
                        controls 
                        autoPlay 
                        className="main-video"
                        src={videoUrl}
                    />
                </div>

                <div className="video-details">
                    <p><strong>ID:</strong> {video.id}</p>
                    <p><strong>Uploaded:</strong> {new Date(video.upload_time).toLocaleString()}</p>
                    {video.client_id && <p><strong>Uploaded by:</strong> {video.client_id}</p>}

                    <div style={{marginTop: '20px', padding: '15px', background: '#f5f5f5', borderRadius: '8px', border: '1px solid #ddd'}}>
                        <h3>Compression Stats</h3>
                        <p><strong>Original Size:</strong> {(video.size / 1024 / 1024).toFixed(2)} MB</p>

                        {video.compressed_size > 0 ? (
                            <>
                                <p><strong>Compressed Size:</strong> {(video.compressed_size / 1024 / 1024).toFixed(2)} MB</p>
                                <p style={{color: '#4caf50', fontWeight: 'bold'}}>
                                    Saved: {((1 - (video.compressed_size / video.size)) * 100).toFixed(1)}% space
                                </p>
                                <a
                                    href={`http://${serverIp}:${serverPort}/content/compressed_${video.filename}`}
                                    target="_blank"
                                    rel="noreferrer"
                                    style={{display: 'inline-block', marginTop: '10px', color: '#2196f3'}}
                                >
                                    View Compressed Video
                                </a>
                            </>
                        ) : (
                            <p><em>Compression pending or failed...</em></p>
                        )}
                    </div>
                </div>
            </div>
        </div>
    )
}

export default VideoPlayer