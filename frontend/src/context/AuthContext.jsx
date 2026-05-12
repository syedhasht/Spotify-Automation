import React, { createContext, useContext, useState, useEffect } from 'react';
import api from '../services/api';
import toast from 'react-hot-toast';

const AuthContext = createContext();

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null);
  const [token, setToken] = useState(localStorage.getItem('access_token'));
  const [loading, setLoading] = useState(true);

  const login = async (username, password) => {
    try {
      const formData = new FormData();
      formData.append('username', username);
      formData.append('password', password);

      const res = await api.post('/auth/login', formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      });
      
      const { access_token, user: userData, session_id } = res.data;
      
      localStorage.setItem('access_token', access_token);
      localStorage.setItem('session_id', session_id);
      
      setToken(access_token);
      setUser(userData);
      toast.success('Welcome back, ' + userData.username);
      return true;
    } catch (err) {
      const detail = err.response?.data?.detail;
      const reason = typeof detail === 'object' ? detail.reason : detail;
      
      let msg = 'Login failed';
      if (reason === 'INVALID_CREDENTIALS') msg = 'Invalid username or password';
      else if (reason === 'USER_INACTIVE') msg = 'Account is deactivated';
      
      toast.error(msg);
      return false;
    }
  };

  const logout = async (silent = false) => {
    try {
      if (!silent) await api.post('/auth/logout');
    } catch (err) {
      console.error("Logout failed", err);
    } finally {
      localStorage.removeItem('access_token');
      localStorage.removeItem('session_id');
      setToken(null);
      setUser(null);
      if (!silent) toast.success('Logged out');
    }
  };

  const logoutAll = async () => {
    try {
      await api.post('/auth/logout_all');
      logout(true);
      toast.success('All sessions revoked');
    } catch (err) {
      toast.error('Failed to revoke sessions');
    }
  };

  // Silent Restore
  useEffect(() => {
    const restoreSession = async () => {
      const savedToken = localStorage.getItem('access_token');
      if (savedToken) {
        try {
          const res = await api.get('/auth/me');
          setUser(res.data);
          setToken(savedToken);
        } catch (err) {
          logout(true);
        }
      }
      // Artificial delay for smooth transition (as requested)
      setTimeout(() => setLoading(false), 800);
    };

    restoreSession();
  }, []);

  // Listen for global auth-expired events (from interceptor)
  useEffect(() => {
    const handleExpired = () => {
      toast.error('Session expired. Please login again.');
      logout(true);
    };
    window.addEventListener('auth-expired', handleExpired);
    return () => window.removeEventListener('auth-expired', handleExpired);
  }, []);

  return (
    <AuthContext.Provider value={{ user, token, loading, login, logout, logoutAll }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
