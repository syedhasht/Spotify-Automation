import React, { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import { useNavigate, Link } from 'react-router-dom';
import { motion } from 'framer-motion';

const Login = () => {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const { login } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setIsSubmitting(true);
    const success = await login(username, password);
    if (success) navigate('/');
    setIsSubmitting(false);
  };

  return (
    <div style={{
      minHeight: '100vh', background: 'var(--background)', display: 'flex',
      alignItems: 'center', justifyContent: 'center', fontFamily: 'Outfit',
      position: 'relative', overflow: 'hidden'
    }}>
      {/* Background Particles */}
      <div className="particle" style={{ top: '20%', left: '10%', animationDelay: '0s' }} />
      <div className="particle" style={{ top: '70%', left: '20%', animationDelay: '5s', width: 6, height: 6 }} />
      <div className="particle" style={{ top: '40%', left: '80%', animationDelay: '2s', background: 'var(--secondary)' }} />
      <div className="particle" style={{ top: '80%', left: '85%', animationDelay: '8s' }} />

      <motion.div 
        initial={{ opacity: 0, scale: 0.9 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.5, ease: [0.34, 1.56, 0.64, 1] }}
        style={{
          width: '100%', maxWidth: 420, padding: 48,
          background: 'rgba(15, 15, 26, 0.7)',
          border: '1px solid var(--primary)',
          borderRadius: 32, boxShadow: '0 30px 100px rgba(0,0,0,0.8)',
          backdropFilter: 'blur(40px)', zIndex: 1, position: 'relative'
        }}
      >
        <div style={{ textAlign: 'center', marginBottom: 48 }}>
          <h1 style={{ 
            color: 'var(--primary)', fontSize: 32, fontWeight: 800, letterSpacing: 6, 
            margin: 0, textShadow: '0 0 20px var(--primary-glow)', fontFamily: 'Oxanium' 
          }}>
            SPOTIFY HUB
          </h1>
          <p style={{ color: 'var(--secondary)', fontSize: 11, fontWeight: 700, letterSpacing: 4, marginTop: 12, opacity: 0.8 }}>
            SECURE ACCESS PORTAL
          </p>
        </div>

        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
          <div>
            <label style={{ color: 'var(--text-secondary)', fontSize: 11, fontWeight: 800, display: 'block', marginBottom: 12, letterSpacing: 3 }}>
              USERNAME
            </label>
            <input
              type="text"
              required
              className="input-glow"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              style={{
                width: '100%', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.1)',
                borderRadius: 16, padding: '16px 24px', color: '#fff', fontSize: 16,
                outline: 'none', fontWeight: 500
              }}
            />
          </div>

          <div style={{ position: 'relative' }}>
            <label style={{ color: 'var(--text-secondary)', fontSize: 11, fontWeight: 800, display: 'block', marginBottom: 12, letterSpacing: 3 }}>
              PASSWORD
            </label>
            <input
              type="password"
              required
              className="input-glow"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              style={{
                width: '100%', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.1)',
                borderRadius: 16, padding: '16px 24px', color: '#fff', fontSize: 16,
                outline: 'none', fontWeight: 500
              }}
            />
          </div>

          <button
            type="submit"
            disabled={isSubmitting}
            className="btn-premium"
            style={{
              width: '100%', height: 64, 
              background: 'linear-gradient(135deg, var(--primary), var(--secondary))',
              color: '#000', border: 'none', borderRadius: 16, fontSize: 16, fontWeight: 900,
              cursor: 'pointer', marginTop: 12, letterSpacing: 2,
              boxShadow: '0 15px 30px rgba(0,255,178,0.3)'
            }}
          >
            {isSubmitting ? 'AUTHENTICATING...' : 'LOGIN TO NODE'}
          </button>
        </form>

        <div style={{ textAlign: 'center', marginTop: 40 }}>
          <p style={{ color: 'var(--text-secondary)', fontSize: 13, fontWeight: 600 }}>
            New operative?{' '}
            <Link to="/signup" style={{ color: 'var(--primary)', textDecoration: 'none', fontWeight: 800, letterSpacing: 1 }}>
              REQUEST ACCESS
            </Link>
          </p>
        </div>
      </motion.div>
    </div>
  );
};

export default Login;
