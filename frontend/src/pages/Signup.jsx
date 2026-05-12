import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import api from '../services/api';
import toast from 'react-hot-toast';

const Signup = () => {
  const [formData, setFormData] = useState({
    username: '',
    email: '',
    full_name: '',
    password: ''
  });
  const [isSubmitting, setIsSubmitting] = useState(false);
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setIsSubmitting(true);
    try {
      await api.post('/auth/signup', formData);
      toast.success('Access granted! Please login.');
      navigate('/login');
    } catch (err) {
      const detail = err.response?.data?.detail;
      const reason = typeof detail === 'object' ? detail.reason : detail;
      toast.error(reason || 'Signup failed');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div style={{
      minHeight: '100vh', background: 'var(--background)', display: 'flex',
      alignItems: 'center', justifyContent: 'center', fontFamily: 'Outfit',
      position: 'relative', overflow: 'hidden'
    }}>
      {/* Background Particles */}
      <div className="particle" style={{ top: '15%', left: '5%', animationDelay: '2s' }} />
      <div className="particle" style={{ top: '65%', left: '15%', animationDelay: '0s', width: 6, height: 6 }} />
      <div className="particle" style={{ top: '35%', left: '75%', animationDelay: '4s', background: 'var(--secondary)' }} />
      <div className="particle" style={{ top: '75%', left: '90%', animationDelay: '6s' }} />

      <motion.div 
        initial={{ opacity: 0, scale: 0.9 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.5, ease: [0.34, 1.56, 0.64, 1] }}
        style={{
          width: '100%', maxWidth: 480, padding: 48,
          background: 'rgba(15, 15, 26, 0.7)',
          border: '1px solid var(--secondary)',
          borderRadius: 32, boxShadow: '0 30px 100px rgba(0,0,0,0.8)',
          backdropFilter: 'blur(40px)', zIndex: 1, position: 'relative'
        }}
      >
        <div style={{ textAlign: 'center', marginBottom: 40 }}>
          <h1 style={{ 
            color: 'var(--secondary)', fontSize: 32, fontWeight: 800, letterSpacing: 6, 
            margin: 0, textShadow: '0 0 20px var(--secondary-glow)', fontFamily: 'Oxanium' 
          }}>
            JOIN THE FLEET
          </h1>
          <p style={{ color: 'var(--primary)', fontSize: 11, fontWeight: 700, letterSpacing: 4, marginTop: 12, opacity: 0.8 }}>
            INITIALIZE OPERATIVE PROFILE
          </p>
        </div>

        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <div>
              <label style={{ color: 'var(--text-secondary)', fontSize: 11, fontWeight: 800, display: 'block', marginBottom: 10, letterSpacing: 2 }}>FULL NAME</label>
              <input
                type="text" required
                className="input-glow"
                value={formData.full_name}
                onChange={(e) => setFormData({...formData, full_name: e.target.value})}
                style={{ width: '100%', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 16, padding: '14px 20px', color: '#fff', fontSize: 15, outline: 'none' }}
              />
            </div>
            <div>
              <label style={{ color: 'var(--text-secondary)', fontSize: 11, fontWeight: 800, display: 'block', marginBottom: 10, letterSpacing: 2 }}>USERNAME</label>
              <input
                type="text" required
                className="input-glow"
                value={formData.username}
                onChange={(e) => setFormData({...formData, username: e.target.value})}
                style={{ width: '100%', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 16, padding: '14px 20px', color: '#fff', fontSize: 15, outline: 'none' }}
              />
            </div>
          </div>

          <div>
            <label style={{ color: 'var(--text-secondary)', fontSize: 11, fontWeight: 800, display: 'block', marginBottom: 10, letterSpacing: 2 }}>EMAIL ADDRESS</label>
            <input
              type="email" required
              className="input-glow"
              value={formData.email}
              onChange={(e) => setFormData({...formData, email: e.target.value})}
              style={{ width: '100%', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 16, padding: '14px 20px', color: '#fff', fontSize: 15, outline: 'none' }}
            />
          </div>

          <div>
            <label style={{ color: 'var(--text-secondary)', fontSize: 11, fontWeight: 800, display: 'block', marginBottom: 10, letterSpacing: 2 }}>PASSWORD</label>
            <input
              type="password" required
              className="input-glow"
              value={formData.password}
              onChange={(e) => setFormData({...formData, password: e.target.value})}
              style={{ width: '100%', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 16, padding: '14px 20px', color: '#fff', fontSize: 15, outline: 'none' }}
            />
          </div>

          <button
            type="submit"
            disabled={isSubmitting}
            className="btn-premium"
            style={{
              width: '100%', height: 64, 
              background: 'linear-gradient(135deg, var(--secondary), var(--primary))',
              color: '#000', border: 'none', borderRadius: 16, fontSize: 16, fontWeight: 900,
              cursor: 'pointer', marginTop: 12, letterSpacing: 2,
              boxShadow: '0 15px 30px rgba(0,212,255,0.3)'
            }}
          >
            {isSubmitting ? 'REQUESTING ACCESS...' : 'INITIALIZE OPERATIVE'}
          </button>
        </form>

        <div style={{ textAlign: 'center', marginTop: 40 }}>
          <p style={{ color: 'var(--text-secondary)', fontSize: 13, fontWeight: 600 }}>
            Already a member?{' '}
            <Link to="/login" style={{ color: 'var(--secondary)', textDecoration: 'none', fontWeight: 800, letterSpacing: 1 }}>
              LOGIN TO NODE
            </Link>
          </p>
        </div>
      </motion.div>
    </div>
  );
};

export default Signup;
