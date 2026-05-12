import axios from 'axios';

const API_BASE = `http://${window.location.hostname}:8000`;

const api = axios.create({
  baseURL: API_BASE,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request Interceptor: Attach JWT
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Response Interceptor: Handle Expiry
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response && error.response.status === 401) {
      const detail = error.response.data.detail;
      const reason = typeof detail === 'object' ? detail.reason : detail;
      
      if (reason === 'TOKEN_EXPIRED' || reason === 'INVALID_TOKEN' || reason === 'SESSION_REVOKED') {
        // Trigger global logout event
        window.dispatchEvent(new Event('auth-expired'));
      }
    }
    return Promise.reject(error);
  }
);

export default api;
export { API_BASE };
