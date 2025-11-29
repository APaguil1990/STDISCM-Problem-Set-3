// import React, { useState, useEffect } from 'react'
// import VideoGrid from './components/VideoGrid'
// import VideoPlayer from './components/VideoPlayer'
// import './styles/App.css'

// const SERVER_IP = 'localhost';
// const SERVER_PORT = '8080';

// function App() {
//   const [videos, setVideos] = useState([])
//   const [selectedVideo, setSelectedVideo] = useState(null)
//   const [stats, setStats] = useState({ queueSize: 0, maxQueue: 10, droppedCount: 0 })
//   const [loading, setLoading] = useState(true)
//   const [error, setError] = useState(null)

//   useEffect(() => {
//     fetchData()
//     const interval = setInterval(fetchData, 2000)
//     return () => clearInterval(interval)
//   }, [])

//   const fetchData = async () => {
//     try {
//       setError(null)
      
//       // Fetch stats
//       const statsResponse = await fetch(`http://${SERVER_IP}:${SERVER_PORT}/api/stats`)
//       if (!statsResponse.ok) throw new Error('Failed to fetch stats')
//       const statsData = await statsResponse.json()
//       setStats(statsData)

//       // Fetch videos list
//       const videosResponse = await fetch(`http://${SERVER_IP}:${SERVER_PORT}/api/videos`)
//       if (!videosResponse.ok) throw new Error('Failed to fetch videos')
//       const videosData = await videosResponse.json()
//       setVideos(videosData)
      
//       setLoading(false)
//     } catch (error) {
//       console.error('Error fetching data:', error)
//       setError(error.message)
//       setLoading(false)
//     }
//   }

//   const calculatePercentage = () => {
//     return stats.maxQueue > 0 ? (stats.queueSize / stats.maxQueue) * 100 : 0
//   }

//   if (loading) {
//     return (
//       <div className="app">
//         <div className="loading">Loading Media Service...</div>
//       </div>
//     )
//   }

//   return (
//     <div className="app">
//       <header className="app-header">
//         <h1>Media Upload Service</h1>
//         <p>Distributed Producer-Consumer System</p>
        
//         {error && <div className="error">Error: {error}</div>}
        
//         {/* Stats Display */}
//         <div className="stats-container">
//           <div className="stat-item">
//             <span className="stat-label">Queue:</span>
//             <span className="stat-value">{stats.queueSize}/{stats.maxQueue}</span>
//           </div>
//           <div className="stat-item">
//             <span className="stat-label">Dropped:</span>
//             <span className="stat-value dropped">{stats.droppedCount}</span>
//           </div>
//         </div>

//         {/* Progress Bar */}
//         <div className="progress-container">
//           <div 
//             className="progress-bar"
//             style={{ width: `${calculatePercentage()}%` }}
//           ></div>
//         </div>
//       </header>
      
//       <main className="app-main">
//         {selectedVideo ? (
//           <VideoPlayer 
//             video={selectedVideo} 
//             onBack={() => setSelectedVideo(null)}
//             serverIp={SERVER_IP}
//             serverPort={SERVER_PORT}
//           />
//         ) : (
//           <VideoGrid 
//             videos={videos}
//             onVideoSelect={setSelectedVideo}
//             serverIp={SERVER_IP}
//             serverPort={SERVER_PORT}
//           />
//         )}
//       </main>
//     </div>
//   )
// }

// export default App  

import React, { useState, useEffect } from 'react'
import './styles/App.css'

function App() {
  const [videos, setVideos] = useState([])
  const [stats, setStats] = useState({ queueSize: 0, maxQueue: 10, droppedCount: 0 })
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetchData()
    const interval = setInterval(fetchData, 2000)
    return () => clearInterval(interval)
  }, [])

  const fetchData = async () => {
    try {
      const statsResponse = await fetch('http://localhost:8080/api/stats')
      const statsData = await statsResponse.json()
      setStats(statsData)

      const videosResponse = await fetch('http://localhost:8080/api/videos')
      const videosData = await videosResponse.json()
      setVideos(videosData)
      setLoading(false)
    } catch (error) {
      console.error('Error fetching data:', error)
      setLoading(false)
    }
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
        <p>Queue: {stats.queueSize}/{stats.maxQueue} | Dropped: {stats.droppedCount}</p>
      </header>
      
      <main className="app-main">
        <h2>Uploaded Videos ({videos.length})</h2>
        <div className="video-list">
          {videos.map(video => (
            <div key={video.id} className="video-item">
              <h3>{video.filename}</h3>
              <p>Size: {(video.size / 1024 / 1024).toFixed(2)} MB</p>
              <p>Client: {video.client_id}</p>
              <video 
                controls 
                width="300"
                src={`http://localhost:8080/content/${video.filename}`}
              />
            </div>
          ))}
        </div>
      </main>
    </div>
  )
}

export default App