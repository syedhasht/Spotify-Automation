import React, { useState, useEffect } from 'react';
import api, { API_BASE } from '../services/api';
import { useAuth } from '../context/AuthContext';
import toast from 'react-hot-toast';

const WS_URL = `ws://${window.location.hostname}:8000/ws/dashboard`;

// ─── Navbar ───────────────────────────────────────────────────────────────────
const Navbar = ({ connectedCount, onLogout, user }) => (
  <div className="glass-nav" style={{
    width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '0 40px', height: 72, flexShrink: 0, zIndex: 100, position: 'sticky', top: 0
  }}>
    <span style={{ 
      color: 'var(--primary)', fontSize: 22, fontWeight: 800, letterSpacing: 4,
      textShadow: '0 0 20px var(--primary-glow)', fontFamily: 'Oxanium'
    }}>
      ⚡ SPOTIFY HUB
    </span>
    <div style={{ display: 'flex', alignItems: 'center', gap: 30 }}>
       <div style={{ display: 'flex', alignItems: 'center', gap: 15 }}>
        <span className="status-dot-active" style={{
          display: 'inline-block', width: 10, height: 10,
          borderRadius: '50%', background: 'var(--primary)',
          boxShadow: '0 0 10px var(--primary)'
        }} />
        <span style={{ color: 'var(--text-secondary)', fontWeight: 700, fontSize: 13, letterSpacing: 2 }}>
          {user?.username?.toUpperCase()} • <span style={{ color: 'var(--primary)' }}>{connectedCount} ONLINE</span>
        </span>
      </div>
      <button className="btn-premium" onClick={onLogout} style={{
        background: 'transparent', border: '1px solid var(--danger)', color: 'var(--danger)',
        padding: '6px 16px', borderRadius: 8, fontSize: 11, fontWeight: 800, cursor: 'pointer'
      }}>LOGOUT</button>
    </div>
  </div>
);

// ─── Device Control Plane ─────────────────────────────────────────────────────
const DeviceControlPlane = ({ devices, selectedIds, onToggle }) => {
  const onlineDevices = devices.filter(d => d.status === 'online');
  
  return (
    <div className="animate-fade-up" style={{ 
      display: 'flex', flexDirection: 'column', gap: 24, 
      background: 'var(--glass)', borderRadius: 24, padding: 32,
      border: '1px solid var(--card-border)'
    }}>
      <div className="section-header" style={{ color: 'var(--secondary)', fontSize: 14, fontWeight: 800 }}>
        DEVICE FLEET
      </div>

      <div style={{ display: 'flex', gap: 10 }}>
        <button className="btn-premium" onClick={() => onToggle('all')} style={{
          flex: 1, background: 'var(--primary)', border: 'none',
          color: '#000', borderRadius: 10, padding: '10px 0', fontSize: 11, fontWeight: 800,
          cursor: 'pointer'
        }}>SELECT ALL</button>
        <button className="btn-premium" onClick={() => onToggle('none')} style={{
          flex: 1, background: 'rgba(255,255,255,0.05)', border: '1px solid var(--danger)',
          color: 'var(--danger)', borderRadius: 10, padding: '10px 0', fontSize: 11, fontWeight: 800,
          cursor: 'pointer'
        }}>CLEAR</button>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, overflowY: 'auto', maxHeight: 'calc(100vh - 350px)', paddingRight: 5 }}>
        {onlineDevices.length === 0 ? (
          <div style={{ padding: '60px 0', textAlign: 'center', position: 'relative', display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
            <div style={{ position: 'relative', width: 40, height: 40, marginBottom: 24 }}>
              <div className="radar-circle" />
              <div className="radar-circle" style={{ animationDelay: '1s' }} />
              <div className="radar-circle" style={{ animationDelay: '2s' }} />
            </div>
            <span style={{ color: 'var(--text-secondary)', fontSize: 12, fontWeight: 800, letterSpacing: 3 }}>NO DEVICES ONLINE</span>
          </div>
        ) : onlineDevices.map(device => {
          const isSelected = selectedIds.includes(device.id);

          return (
            <div key={device.id} className="card-hover" style={{
              display: 'flex', alignItems: 'center', gap: 12,
              background: isSelected ? 'rgba(0,255,178,0.08)' : 'rgba(255,255,255,0.02)',
              border: `1px solid ${isSelected ? 'var(--primary)' : 'rgba(255,255,255,0.05)'}`,
              borderRadius: 12, padding: '16px',
              cursor: 'pointer', position: 'relative', overflow: 'hidden'
            }} onClick={() => onToggle(device.id)}>
              <div style={{ 
                position: 'absolute', left: 0, top: 0, bottom: 0, 
                width: isSelected ? 4 : 0, background: 'var(--primary)',
                transition: 'width 0.3s cubic-bezier(0.34, 1.56, 0.64, 1)'
              }} />
              <div style={{ display: 'flex', flexDirection: 'column', flex: 1, overflow: 'hidden' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ color: isSelected ? 'var(--primary)' : '#fff', fontSize: 14, fontWeight: 800 }}>{device.name || "Unknown"}</span>
                  <span style={{ fontSize: 9, color: 'var(--primary)', fontWeight: 800, letterSpacing: 1 }}>ONLINE</span>
                </div>
                <div style={{ display: 'flex', gap: 12, marginTop: 4 }}>
                  <span style={{ color: 'var(--text-secondary)', fontSize: 10 }}>ID: {device.id?.substring(device.id.length - 8)}</span>
                  <span style={{ color: 'var(--text-secondary)', fontSize: 10 }}>v{device.app_version || '1.0'}</span>
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

const Dashboard = () => {
  const { user, logout, logoutAll, token } = useAuth();
  const [devices, setDevices] = useState([]);
  const [selectedIds, setSelectedIds] = useState([]);
  const [activeMode, setActiveMode] = useState('SONG');
  const [isSearching, setIsSearching] = useState(false);
  const [isScheduled, setIsScheduled] = useState(false);
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [autoLikeCount, setAutoLikeCount] = useState(0);
  const [sessions, setSessions] = useState([]);
  const [playlistName, setPlaylistName] = useState('');
  const [songsToAdd, setSongsToAdd] = useState(0);
  const [enablePlaylistAuto, setEnablePlaylistAuto] = useState(false);
  const isScheduledSession = activeMode === 'SONG' && isScheduled === true;

  const fetchSessions = async () => {
    try {
      const res = await api.get('/sessions');
      setSessions(res.data);
    } catch (err) {}
  };

  useEffect(() => {
    if (!token) return;
    
    let ws;
    const connect = () => {
      // Pass token in query params for WS auth
      ws = new WebSocket(`${WS_URL}?token=${token}`);
      ws.onmessage = (e) => {
        try {
          const msg = JSON.parse(e.data);
          if (msg.type === 'REGISTRY_UPDATE') setDevices(msg.devices || []);
          if (msg.type === 'LOG' && (msg.step === 'FINAL' || msg.status === 'FAILED')) setIsSearching(false);
        } catch (err) {}
      };
      ws.onclose = (e) => {
        if (e.code === 1008) {
          toast.error("WebSocket Authentication Failed");
        } else {
          setTimeout(connect, 3000);
        }
      };
    };
    connect();
    fetchSessions();
    
    const interval = setInterval(fetchSessions, 10000);
    return () => {
        if (ws) ws.close();
        clearInterval(interval);
    };
  }, [token]);

  const [artistAction, setArtistAction] = useState('PLAY_ARTIST_CATALOG');

  const handleSearch = (q) => {
    if (!q) return;
    if (selectedIds.length === 0) {
      toast.error("SELECT AT LEAST ONE DEVICE");
      return;
    }
    if (isScheduled && (!startTime || !endTime)) {
      toast.error("PLEASE SELECT BOTH START AND END TIMES");
      return;
    }
    if (enablePlaylistAuto && (!playlistName || parseInt(songsToAdd) <= 0)) {
      toast.error("ENTER PLAYLIST NAME AND SONGS > 0");
      return;
    }

    setIsSearching(true);
    
    let action = activeMode.toLowerCase();
    let payload = { query: q };

    if (activeMode === 'ARTIST') {
      action = 'artist';
      payload = { query: q, artist_action: artistAction };
    } else if (activeMode === 'PLAYLIST') {
      action = 'play_playlist';
    } else if (activeMode === 'ALBUM') {
      action = 'play_album';
    } else {
      action = 'play_from_search';
    }

    selectedIds.forEach(async (device_id) => {
      try {
        const enforcedLikeCount = isScheduledSession ? 0 : (parseInt(autoLikeCount) || 0);
        const enforcedPayload = {
          ...payload,
          auto_like_count: enforcedLikeCount,
          playlist_automation_enabled: enablePlaylistAuto,
          playlist_name: enablePlaylistAuto ? playlistName : '',
          songs_to_add: enablePlaylistAuto ? (parseInt(songsToAdd) || 0) : 0,
          session_mode: isScheduledSession
        };
        if (isScheduled) {
            await api.post('/sessions', {
                device_id,
                action,
                payload: enforcedPayload,
                start_time: new Date(startTime).toISOString(),
                end_time: new Date(endTime).toISOString(),
                auto_like_count: enforcedLikeCount,
                playlist_automation_enabled: enablePlaylistAuto,
                playlist_name: enablePlaylistAuto ? playlistName : '',
                songs_to_add: enablePlaylistAuto ? (parseInt(songsToAdd) || 0) : 0
            });
            toast.success(`SCHEDULED: ${action.toUpperCase()}`);
            fetchSessions();
        } else {
            await api.post('/send_command', { 
              device_id, 
              action, 
              payload: enforcedPayload
            });
            toast.success(`DISPATCHED: ${action.toUpperCase()}`);
        }
      } catch (err) { console.error(err); }
    });
    
    setTimeout(() => setIsSearching(false), 2000);
  };

  const modes = [
    { id: 'SONG', label: '🎵 SONG' },
    { id: 'PLAYLIST', label: '📋 PLAYLIST' },
    { id: 'ALBUM', label: '💿 ALBUM' },
    { id: 'ARTIST', label: '🎤 ARTIST' }
  ];

  return (
    <div style={{ background: 'var(--background)', minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <Navbar connectedCount={devices.filter(d => d.status === 'online').length} onLogout={() => logout()} user={user} />

      <div style={{ display: 'grid', gridTemplateColumns: window.innerWidth < 1000 ? '1fr' : '360px 1fr', gap: 32, padding: '40px', flex: 1 }}>
        <DeviceControlPlane devices={devices} selectedIds={selectedIds} onToggle={(id) => {
          if (id === 'all') setSelectedIds(devices.filter(d => d.status === 'online').map(d => d.id));
          else if (id === 'none') setSelectedIds([]);
          else setSelectedIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
        }} />

        <div className="animate-fade-up" style={{ display: 'flex', flexDirection: 'column', gap: 32, animationDelay: '0.2s' }}>
          <div className="section-header" style={{ color: 'var(--secondary)', fontSize: 14, fontWeight: 800 }}>COMMAND CENTER</div>
          
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
            {modes.map(m => (
              <button key={m.id} onClick={() => setActiveMode(m.id)} className="btn-premium" style={{
                height: 72, background: activeMode === m.id ? 'var(--primary)' : 'var(--glass)',
                color: activeMode === m.id ? '#000' : 'var(--primary)',
                border: `2px solid var(--primary)`, borderRadius: 16, fontWeight: 800, cursor: 'pointer',
                animation: activeMode === m.id ? 'glowPulse 2s infinite' : 'none',
                fontSize: 13, letterSpacing: 1
              }}>{m.label}</button>
            ))}
          </div>

          {activeMode === 'ARTIST' && (
            <div style={{ marginTop: 8, animation: 'fadeSlideUp 0.4s ease' }}>
              <div style={{ color: 'var(--secondary)', fontSize: 12, fontWeight: 800, marginBottom: 12, letterSpacing: 2 }}>ARTIST ACTION</div>
              <select 
                value={artistAction}
                onChange={(e) => setArtistAction(e.target.value)}
                className="input-glow"
                style={{
                  width: '100%', height: 72, background: 'var(--glass)', border: '2px solid var(--secondary)',
                  borderRadius: 20, color: '#fff', fontSize: 16, fontWeight: 700, padding: '0 24px', outline: 'none'
                }}
              >
                <option value="FOLLOW_ARTIST">FOLLOW ARTIST</option>
                <option value="PLAY_ARTIST_CATALOG">PLAY CATALOG</option>
                <option value="PLAY_THIS_IS_ARTIST">PLAY "THIS IS" PLAYLIST</option>
                <option value="PLAY_ARTIST_RADIO">PLAY RADIO STATION</option>
              </select>
            </div>
          )}

          <div style={{ position: 'relative' }}>
            <input 
              id="main-search" 
              className="input-glow"
              onKeyDown={e => e.key === 'Enter' && handleSearch(e.target.value)} 
              placeholder="ENTER SEARCH QUERY..." 
              style={{ 
                width: '100%', height: 72, background: 'var(--glass)', 
                border: '2px solid var(--primary)', borderRadius: 20, 
                color: '#fff', fontSize: 18, padding: '0 100px 0 28px',
                fontWeight: 600, letterSpacing: 2
              }} 
            />
            <button 
              onClick={() => handleSearch(document.getElementById('main-search').value)} 
              className="btn-premium"
              style={{ 
                position: 'absolute', right: 10, top: 10, bottom: 10, 
                background: 'linear-gradient(135deg, var(--primary), var(--secondary))', 
                color: '#000', border: 'none', borderRadius: 14, padding: '0 28px', 
                fontSize: 24, fontWeight: 900, cursor: 'pointer' 
              }}
            >
              ▶
            </button>
          </div>

          {activeMode === 'SONG' && !isScheduledSession && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 20, background: 'rgba(255,255,255,0.02)', padding: '16px 24px', borderRadius: 20, border: '1px solid var(--card-border)' }}>
                <div style={{ flex: 1 }}>
                    <div style={{ color: 'var(--primary)', fontSize: 11, fontWeight: 800, letterSpacing: 2, marginBottom: 4 }}>LIKE SONG</div>
                    <div style={{ color: 'var(--text-secondary)', fontSize: 10 }}>Add this song to your library</div>
                </div>
                <div 
                    onClick={() => setAutoLikeCount(autoLikeCount > 0 ? 0 : 1)}
                    style={{ 
                        width: 48, height: 24, borderRadius: 12, 
                        background: autoLikeCount > 0 ? 'var(--primary)' : 'rgba(255,255,255,0.1)',
                        position: 'relative', cursor: 'pointer',
                        transition: 'background 0.3s ease'
                    }}
                >
                    <div style={{
                        width: 20, height: 20, borderRadius: '50%', background: '#fff',
                        position: 'absolute', top: 2, left: autoLikeCount > 0 ? 26 : 2,
                        transition: 'left 0.3s cubic-bezier(0.34, 1.56, 0.64, 1)',
                        boxShadow: '0 2px 5px rgba(0,0,0,0.2)'
                    }} />
                </div>
            </div>
          )}

          {activeMode === 'PLAYLIST' && (
            <div style={{ marginTop: 0, animation: 'fadeSlideUp 0.4s ease' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 20, background: 'rgba(255,255,255,0.02)', padding: '16px 24px', borderRadius: 20, border: '1px solid var(--card-border)' }}>
                    <div style={{ flex: 1 }}>
                        <div style={{ color: 'var(--primary)', fontSize: 11, fontWeight: 800, letterSpacing: 2, marginBottom: 4 }}>AUTO LIKE SONGS</div>
                        <div style={{ color: 'var(--text-secondary)', fontSize: 10 }}>Automatically add songs to library during playback</div>
                    </div>
                    <input 
                        type="number" 
                        value={autoLikeCount} 
                        onChange={e => setAutoLikeCount(e.target.value)}
                        min="0"
                        style={{ width: 80, height: 48, background: 'var(--glass)', border: '2px solid var(--primary)', borderRadius: 12, color: '#fff', textAlign: 'center', fontWeight: 800, fontSize: 18 }}
                    />
                </div>
            </div>
          )}

          {activeMode === 'PLAYLIST' && (
            <div className="animate-fade-up" style={{ 
                background: 'rgba(255,255,255,0.03)', 
                padding: 24, 
                borderRadius: 24, 
                border: '1px solid var(--card-border)',
                display: 'flex',
                flexDirection: 'column',
                gap: 16
            }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <div style={{ color: 'var(--secondary)', fontSize: 12, fontWeight: 800, letterSpacing: 2 }}>PLAYLIST AUTOMATION</div>
                    <div 
                        onClick={() => {
                            setEnablePlaylistAuto(!enablePlaylistAuto);
                            if (enablePlaylistAuto) {
                                setPlaylistName('');
                                setSongsToAdd(0);
                            }
                        }}
                        style={{ 
                            width: 48, height: 24, borderRadius: 12, 
                            background: enablePlaylistAuto ? 'var(--primary)' : 'rgba(255,255,255,0.1)',
                            position: 'relative', cursor: 'pointer',
                            transition: 'background 0.3s ease'
                        }}
                    >
                        <div style={{
                            width: 20, height: 20, borderRadius: '50%', background: '#fff',
                            position: 'absolute', top: 2, left: enablePlaylistAuto ? 26 : 2,
                            transition: 'left 0.3s cubic-bezier(0.34, 1.56, 0.64, 1)',
                            boxShadow: '0 2px 5px rgba(0,0,0,0.2)'
                        }} />
                    </div>
                </div>
                
                {enablePlaylistAuto && (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, animation: 'fadeSlideUp 0.4s ease' }}>
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 120px', gap: 16 }}>
                            <input 
                                placeholder="TARGET PLAYLIST NAME..."
                                value={playlistName}
                                onChange={e => setPlaylistName(e.target.value)}
                                className="input-glow"
                                style={{ height: 56, background: 'var(--glass)', border: '1px solid var(--card-border)', borderRadius: 16, color: '#fff', padding: '0 20px', fontSize: 14 }}
                            />
                            <div style={{ position: 'relative' }}>
                                <input 
                                    type="number"
                                    placeholder="SONGS"
                                    value={songsToAdd}
                                    onChange={e => setSongsToAdd(e.target.value)}
                                    className="input-glow"
                                    style={{ width: '100%', height: 56, background: 'var(--glass)', border: '1px solid var(--card-border)', borderRadius: 16, color: '#fff', padding: '0 12px', fontSize: 14, textAlign: 'center' }}
                                />
                                <div style={{ position: 'absolute', top: -8, left: 12, background: 'var(--background)', padding: '0 6px', fontSize: 9, color: 'var(--secondary)', fontWeight: 800 }}>SONGS</div>
                            </div>
                        </div>

                        </div>
                )}
            </div>
          )}

          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
             <label style={{ color: '#fff', fontSize: 14, fontWeight: 800, display: 'flex', alignItems: 'center', gap: 8 }}>
                <input type="checkbox" checked={isScheduled} onChange={e => setIsScheduled(e.target.checked)} style={{ width: 18, height: 18 }} />
                SCHEDULE SESSION
             </label>
             {isScheduled && (
                 <div style={{ display: 'flex', gap: 12, flex: 1 }}>
                     <input type="datetime-local" value={startTime} onChange={e => setStartTime(e.target.value)} className="input-glow" style={{ flex: 1, height: 48, background: 'var(--glass)', border: '1px solid var(--card-border)', borderRadius: 12, color: '#fff', padding: '0 16px' }} />
                     <input type="datetime-local" value={endTime} onChange={e => setEndTime(e.target.value)} className="input-glow" style={{ flex: 1, height: 48, background: 'var(--glass)', border: '1px solid var(--card-border)', borderRadius: 12, color: '#fff', padding: '0 16px' }} />
                 </div>
             )}
          </div>

          {sessions.length > 0 && (
             <div style={{ marginTop: 20 }}>
                <div className="section-header" style={{ color: 'var(--secondary)', fontSize: 14, fontWeight: 800 }}>ACTIVE & UPCOMING SESSIONS</div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 12 }}>
                   {sessions.map(s => (
                       <div key={s.id} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: 16, background: 'rgba(255,255,255,0.02)', border: '1px solid var(--card-border)', borderRadius: 12 }}>
                           <div style={{ flex: 1 }}>
                               <div style={{ color: '#fff', fontWeight: 800, fontSize: 14 }}>Device: {s.device_id.substring(s.device_id.length - 6)}</div>
                               <div style={{ color: 'var(--text-secondary)', fontSize: 12 }}>
                                   {new Date(s.start_time).toLocaleString()} - {new Date(s.end_time).toLocaleString()}
                               </div>
                               {(s.status === 'running' || s.playback_state !== 'UNKNOWN') && (
                                   <div style={{ display: 'flex', gap: 12, marginTop: 8 }}>
                                       <span style={{ fontSize: 11, background: 'var(--glass)', padding: '4px 8px', borderRadius: 4, color: 'var(--primary)' }}>STATE: {s.playback_state || 'WAITING'}</span>
                                       <span style={{ fontSize: 11, background: 'var(--glass)', padding: '4px 8px', borderRadius: 4, color: '#fff' }}>LOOPS: {s.loop_count || 0}</span>
                                       {s.auto_like_count > 0 && (
                                           <span style={{ fontSize: 11, background: 'var(--glass)', padding: '4px 8px', borderRadius: 4, color: '#ff4081', border: '1px solid #ff4081' }}>❤️ LIKES: {s.liked_count || 0}/{s.auto_like_count}</span>
                                       )}
                                       <span style={{ fontSize: 11, background: 'var(--glass)', padding: '4px 8px', borderRadius: 4, color: '#fff' }}>ELAPSED: {s.elapsed_time ? Math.round(s.elapsed_time / 60000) : 0}m</span>
                                       <span style={{ fontSize: 11, background: 'var(--glass)', padding: '4px 8px', borderRadius: 4, color: 'var(--secondary)' }}>REMAINING: {s.remaining_time ? Math.round(s.remaining_time / 60000) : 0}m</span>
                                   </div>
                               )}
                           </div>
                           <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
                               <span style={{ color: s.status === 'running' ? 'var(--primary)' : s.status === 'scheduled' ? 'var(--secondary)' : 'var(--text-secondary)', fontWeight: 800, fontSize: 12 }}>
                                   {s.status.toUpperCase()}
                               </span>
                               {(s.status === 'running' || s.status === 'scheduled') && (
                                   <button onClick={async () => {
                                       await api.post(`/sessions/${s.id}/stop`);
                                       fetchSessions();
                                   }} className="btn-premium" style={{ background: 'transparent', border: '1px solid var(--danger)', color: 'var(--danger)', padding: '6px 12px', borderRadius: 8, fontSize: 10, fontWeight: 800, cursor: 'pointer' }}>STOP</button>
                               )}
                           </div>
                       </div>
                   ))}
                </div>
             </div>
          )}

        </div>
      </div>
    </div>
  );
};

export default Dashboard;
