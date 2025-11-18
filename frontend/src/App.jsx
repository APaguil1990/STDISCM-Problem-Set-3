import React, { useState, useEffect } from 'react'
import VideoGrid from './components/VideoGrid'
import VideoPlayer from './components/VideoPlayer'
import './styles/App.css'

function App() {
  const [videos, setVideos] = useState([])
  const [selectedVideo, setSelectedVideo] = useState(null)

  useEffect(() => {
    fetchVideos()
    const interval = setInterval(fetchVideos, 5000)
    return () => clearInterval(interval)
  }, [])

  const fetchVideos = async () => {
    try {
      // This would normally call your backend API
      // For now, we'll simulate with mock data
      const mockVideos = [
        { id: '1', filename: 'video1.mp4', upload_time: new Date().toISOString(), size: 1024000 },
        { id: '2', filename: 'video2.mp4', upload_time: new Date().toISOString(), size: 2048000 },
      ]
      setVideos(mockVideos)
    } catch (error) {
      console.error('Error fetching videos:', error)
    }
  }

  return (
    <div className="app">
      <header className="app-header">
        <h1>Media Upload Service</h1>
        <p>Distributed Producer-Consumer System</p>
      </header>
      
      <main className="app-main">
        {selectedVideo ? (
          <VideoPlayer 
            video={selectedVideo} 
            onBack={() => setSelectedVideo(null)}
          />
        ) : (
          <VideoGrid 
            videos={videos}
            onVideoSelect={setSelectedVideo}
          />
        )}
      </main>
    </div>
  )
}

export default App