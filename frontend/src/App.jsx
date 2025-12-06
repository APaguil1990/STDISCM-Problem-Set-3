import React, { useState, useEffect } from 'react'
import VideoGrid from './components/VideoGrid'
import VideoPlayer from './components/VideoPlayer'
import './styles/App.css'
import configData from '../config.json'

// Load server configuration from config.json with fallback to defaults
const SERVER_IP = configData?.SERVER_IP || 'localhost';
const SERVER_PORT = configData?.SERVER_PORT || '8080';

function App() {
  const [videos, setVideos] = useState([])
  const [selectedVideo, setSelectedVideo] = useState(null)
  const [stats, setStats] = useState({ queueSize: 0, maxQueue: 10, droppedCount: 0 })
  const [lastDroppedCount, setLastDroppedCount] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    fetchData()
    const interval = setInterval(fetchData, 2000)
    return () => clearInterval(interval)
  }, [])

  const fetchData = async () => {
    try {
      setError(null)
      
      // Fetch stats
      const statsResponse = await fetch(`http://${SERVER_IP}:${SERVER_PORT}/api/stats`)
      if (!statsResponse.ok) throw new Error('Failed to fetch stats')
      const statsData = await statsResponse.json()
      setStats(statsData)

      // Check for new dropped videos
      if (statsData.droppedCount > lastDroppedCount) {
        setLastDroppedCount(statsData.droppedCount)
        triggerDropAlert()
      }

      // Fetch videos list
      const videosResponse = await fetch(`http://${SERVER_IP}:${SERVER_PORT}/api/videos`)
      if (!videosResponse.ok) throw new Error('Failed to fetch videos')
      const videosData = await videosResponse.json()
      setVideos(videosData)
      
      setLoading(false)
    } catch (error) {
      console.error('Error fetching data:', error)
      setError(error.message)
      setLoading(false)
    }
  }

  const triggerDropAlert = () => {
    // Visual alert for dropped videos
    const alertElement = document.createElement('div')
    alertElement.style.cssText = `
      position: fixed;
      top: 20px;
      right: 20px;
      background: #ff4444;
      color: white;
      padding: 15px;
      border-radius: 5px;
      z-index: 1000;
      animation: flash 1s 3;
      box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
    `
    alertElement.textContent = '⚠️ Video dropped due to queue overflow!'
    document.body.appendChild(alertElement)
    
    setTimeout(() => {
      if (document.body.contains(alertElement)) {
        document.body.removeChild(alertElement)
      }
    }, 3000)
  }

  const calculatePercentage = () => {
    return stats.maxQueue > 0 ? (stats.queueSize / stats.maxQueue) * 100 : 0
  }

  if (loading) {
    return (
      <div className="app">
        <header className="app-header">
          <h1>Media Upload Service</h1>
          <p>Loading...</p>
        </header>
      </div>
    )
  }

  return (
    <div className="app">
      <header className="app-header">
        <h1>Media Upload Service</h1>
        <p>Distributed Producer-Consumer System</p>
        
        {error && (
          <div className="error-banner">
            Error: {error}. Make sure the backend server is running on {SERVER_IP}:{SERVER_PORT}
          </div>
        )}
        
        {/* Stats Display */}
        <div className="stats-container">
          <div className="stat-item">
            <span className="stat-label">Queue:</span>
            <span className="stat-value">{stats.queueSize}/{stats.maxQueue}</span>
          </div>
          <div className="stat-item">
            <span className="stat-label">Dropped:</span>
            <span className="stat-value dropped">{stats.droppedCount}</span>
          </div>
          <div className="stat-item">
            <span className="stat-label">Videos:</span>
            <span className="stat-value">{videos.length}</span>
          </div>
        </div>

        {/* Progress Bar */}
        <div className="progress-container">
          <div 
            className="progress-bar"
            style={{ width: `${calculatePercentage()}%` }}
          ></div>
        </div>
      </header>
      
      <main className="app-main">
        {selectedVideo ? (
          <VideoPlayer 
            video={selectedVideo} 
            onBack={() => setSelectedVideo(null)}
            serverIp={SERVER_IP}
            serverPort={SERVER_PORT}
          />
        ) : (
          <VideoGrid 
            videos={videos}
            onVideoSelect={setSelectedVideo}
            serverIp={SERVER_IP}
            serverPort={SERVER_PORT}
          />
        )}
      </main>
    </div>
  )
}

export default App
