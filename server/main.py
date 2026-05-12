from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, Depends, status
from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm
from jose import JWTError, jwt
from passlib.context import CryptContext
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import json
import time
import datetime
import uuid
import pytz
import asyncio
import os
from typing import List, Optional
from contextlib import asynccontextmanager
from sqlalchemy import create_engine, Column, Integer, String, DateTime, ForeignKey, Text, JSON, Boolean, text, or_, func
from sqlalchemy.orm import sessionmaker, Session, declarative_base
from logger import (
    log_ws, log_device, log_db, log_system, log_exception, 
    log_step_started, log_step_ok, log_step_warning, log_step_failed, print_banner, 
    log_command_done, log_service_completion
)

# --- TIMEZONE CONFIG ---
LOCAL_TZ = pytz.timezone("Asia/Karachi")

def now_local():
    return datetime.datetime.now(LOCAL_TZ)

# --- AUTH CONFIG ---
JWT_SECRET = "09d25e094faa6ca2556c818166b7a9563b93f7099f6f0f4caa6cf63b88e8d3e7" # Production-grade secret (would use env in real prod)
JWT_ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 60 * 24 * 7 # 7 Days for persistence

pwd_context = CryptContext(schemes=["pbkdf2_sha256"], deprecated="auto")
# Removed oauth2_scheme to hide Swagger Authorize dialog

# --- DATABASE CONFIG ---
SQLALCHEMY_DATABASE_URL = "sqlite:///./automation.db"
engine = create_engine(SQLALCHEMY_DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()

# --- MODELS ---
class Device(Base):
    __tablename__ = "devices"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, unique=True, index=True)
    device_name = Column(String)
    status = Column(String, index=True)
    last_seen = Column(DateTime, default=now_local, index=True)
    app_version = Column(String, nullable=True)
    battery = Column(Integer, nullable=True)
    network_type = Column(String, nullable=True)
    capabilities = Column(JSON, nullable=True)
    owner_id = Column(String, ForeignKey("users.id", ondelete="SET NULL"), index=True)

class Task(Base):
    __tablename__ = "tasks"
    id = Column(String, primary_key=True, default=lambda: uuid.uuid4().hex)
    task_name = Column(String)
    action_type = Column(String, index=True)
    search_query = Column(String, nullable=True)
    action_params = Column(JSON, nullable=True)
    status = Column(String, default="pending", index=True)
    is_system = Column(Boolean, default=False, index=True)
    created_at = Column(DateTime, default=now_local)
    auto_like_count = Column(Integer, default=0)
    playlist_name = Column(String, nullable=True)
    songs_to_add = Column(Integer, default=0)
    create_if_missing = Column(Boolean, default=True)
    owner_id = Column(String, ForeignKey("users.id", ondelete="SET NULL"), index=True)

class Run(Base):
    __tablename__ = "runs"
    id = Column(String, primary_key=True, default=lambda: uuid.uuid4().hex)
    task_id = Column(String, ForeignKey("tasks.id", ondelete="CASCADE"), index=True)
    device_id = Column(String, ForeignKey("devices.device_id", ondelete="CASCADE"), index=True)
    status = Column(String, index=True) # running/success/failed
    start_time = Column(DateTime, default=now_local)
    end_time = Column(DateTime, nullable=True)
    final_reason = Column(Text, nullable=True)
    owner_id = Column(String, ForeignKey("users.id", ondelete="SET NULL"), index=True)

class User(Base):
    __tablename__ = "users"
    id = Column(String, primary_key=True, default=lambda: uuid.uuid4().hex)
    username = Column(String, unique=True, index=True, nullable=False)
    email = Column(String, unique=True, index=True, nullable=False)
    full_name = Column(String, nullable=True)
    password = Column(String, nullable=False) # Plain text as requested
    created_at = Column(DateTime, default=now_local)
    last_login = Column(DateTime, nullable=True)
    is_active = Column(Boolean, default=True)

class UserSession(Base):
    __tablename__ = "user_sessions"
    id = Column(String, primary_key=True, default=lambda: uuid.uuid4().hex)
    session_id = Column(String, unique=True, index=True, nullable=False)
    user_id = Column(String, ForeignKey("users.id", ondelete="CASCADE"), index=True)
    jwt_jti = Column(String, unique=True, index=True, nullable=False)
    issued_at = Column(DateTime, default=now_local)
    expires_at = Column(DateTime, nullable=False)
    revoked_at = Column(DateTime, nullable=True)
    is_active = Column(Boolean, default=True)
    ip_address = Column(String, nullable=True)
    user_agent = Column(String, nullable=True)
    created_at = Column(DateTime, default=now_local)

class RunEvent(Base):
    __tablename__ = "run_events"
    id = Column(String, primary_key=True, default=lambda: uuid.uuid4().hex)
    run_id = Column(String, ForeignKey("runs.id", ondelete="CASCADE"), index=True)
    event_type = Column(String, index=True)
    node = Column(String, nullable=True)
    payload = Column(JSON, nullable=True)
    timestamp = Column(DateTime, default=now_local, index=True)

class ScheduledSession(Base):
    __tablename__ = "scheduled_sessions"
    id = Column(String, primary_key=True, default=lambda: uuid.uuid4().hex)
    user_id = Column(String, ForeignKey("users.id", ondelete="CASCADE"), index=True)
    device_id = Column(String, ForeignKey("devices.device_id", ondelete="CASCADE"), index=True)
    task_id = Column(String, ForeignKey("tasks.id", ondelete="CASCADE"), index=True)
    start_time = Column(DateTime, nullable=False, index=True)
    end_time = Column(DateTime, nullable=False)
    status = Column(String, default="scheduled", index=True) # scheduled, running, done, failed
    
    # Session Telemetry
    total_duration = Column(Integer, default=0)
    elapsed_time = Column(Integer, default=0)
    remaining_time = Column(Integer, default=0)
    loop_count = Column(Integer, default=0)
    playback_state = Column(String, default="UNKNOWN")
    auto_like_count = Column(Integer, default=0)
    liked_count = Column(Integer, default=0)

class AuthEvent(Base):
    __tablename__ = "auth_events"
    id = Column(String, primary_key=True, default=lambda: uuid.uuid4().hex)
    event_type = Column(String, index=True) # LOGIN_SUCCESS, LOGIN_FAILED, LOGOUT, etc.
    username = Column(String, index=True)
    ip_address = Column(String, nullable=True)
    session_id = Column(String, nullable=True)
    timestamp = Column(DateTime, default=now_local)

from sqlalchemy import inspect

from sqlalchemy.pool import NullPool

def auto_migrate():
    """Manually add missing columns for SQLite since create_all doesn't handle migrations."""
    global engine, SessionLocal
    
    # 1. Critical Check: Task ID type (String vs Integer)
    # Use a temporary NULL POOL engine for inspection to avoid locking the file on Windows
    temp_engine = create_engine(SQLALCHEMY_DATABASE_URL, poolclass=NullPool)
    inspector = inspect(temp_engine)
    
    needs_rebuild = False
    try:
        if "tasks" in inspector.get_table_names():
            columns = inspector.get_columns("tasks")
            id_col = next((c for c in columns if c["name"] == "id"), None)
            if id_col and not str(id_col["type"]).upper().startswith("VARCHAR") and not str(id_col["type"]).upper().startswith("STRING"):
                needs_rebuild = True
        
        if "users" in inspector.get_table_names():
            user_columns = inspector.get_columns("users")
            # FORCE REBUILD if old hashed_password column exists
            if any(c["name"] == "hashed_password" for c in user_columns):
                needs_rebuild = True
    except Exception:
        pass
    finally:
        temp_engine.dispose()

    if needs_rebuild:
        log_db("⚠️ CRITICAL: Database schema is incompatible. Rebuilding...")
        db_file = SQLALCHEMY_DATABASE_URL.replace("sqlite:///./", "")
        
        # Dispose the GLOBAL engine just in case it was touched
        engine.dispose()
        
        if os.path.exists(db_file):
            try:
                os.rename(db_file, f"{db_file}.old_{int(time.time())}")
            except PermissionError:
                log_db("❌ FATAL: Could not rename database. Close any DB browsers (SQLite Studio, etc) and try again.")
                raise

        # Recreate engine and session factory after rename
        engine = create_engine(SQLALCHEMY_DATABASE_URL, connect_args={"check_same_thread": False})
        SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
        Base.metadata.create_all(bind=engine)
        log_db("✅ Database rebuilt successfully for UUID support.")
        return

    # 2. Add missing columns (Normal migrations)
    migrations = {
        "devices": {
            "device_name": "TEXT",
            "battery": "INTEGER",
            "network_type": "TEXT",
            "owner_id": "TEXT"
        },
        "tasks": {
            "is_system": "BOOLEAN DEFAULT 0",
            "owner_id": "TEXT",
            "auto_like_count": "INTEGER DEFAULT 0",
            "playlist_name": "TEXT",
            "songs_to_add": "INTEGER DEFAULT 0",
            "create_if_missing": "BOOLEAN DEFAULT 1"
        },
        "runs": {
            "owner_id": "TEXT"
        },
        "users": {
            "password": "TEXT"
        },
        "scheduled_sessions": {
            "total_duration": "INTEGER DEFAULT 0",
            "elapsed_time": "INTEGER DEFAULT 0",
            "remaining_time": "INTEGER DEFAULT 0",
            "loop_count": "INTEGER DEFAULT 0",
            "playback_state": "TEXT DEFAULT 'UNKNOWN'",
            "auto_like_count": "INTEGER DEFAULT 0",
            "liked_count": "INTEGER DEFAULT 0",
            "playlist_name": "TEXT",
            "songs_to_add": "INTEGER DEFAULT 0",
            "create_if_missing": "BOOLEAN DEFAULT 1"
        }
    }
    with engine.connect() as conn:
        for table, cols in migrations.items():
            for col, col_type in cols.items():
                try:
                    conn.execute(text(f"ALTER TABLE {table} ADD COLUMN {col} {col_type}"))
                    conn.commit()
                    log_db(f"✅ Added missing column: {table}.{col}")
                except Exception:
                    pass
        
        # Ensure indexes exist for auth performance
        indexes = [
            ("idx_users_username", "users", "username"),
            ("idx_users_email", "users", "email"),
            ("idx_sessions_jti", "user_sessions", "jwt_jti"),
            ("idx_sessions_sid", "user_sessions", "session_id"),
            ("idx_sessions_uid", "user_sessions", "user_id"),
            ("idx_devices_owner", "devices", "owner_id"),
            ("idx_tasks_owner", "tasks", "owner_id")
        ]
        for idx_name, table, col in indexes:
            try:
                conn.execute(text(f"CREATE INDEX {idx_name} ON {table} ({col})"))
                conn.commit()
            except Exception:
                pass

# Run migration logic
auto_migrate()
Base.metadata.create_all(bind=engine)

def log_auth_event(db: Session, event_type: str, username: str, ip: str = None, session_id: str = None):
    try:
        event = AuthEvent(event_type=event_type, username=username, ip_address=ip, session_id=session_id)
        db.add(event)
        db.commit()
        log_system(f"🔐 AUTH :: {event_type} - {username} ({ip or 'N/A'})")
    except Exception as e:
        log_exception("AUTH", f"Failed to log auth event: {e}")

# --- SCHEMAS ---
class CommandRequest(BaseModel):
    device_id: str
    action: str
    payload: dict = {}

class TaskCreate(BaseModel):
    task_name: str
    action_type: str
    search_query: Optional[str] = None
    action_params: Optional[dict] = {}

class SessionCreate(BaseModel):
    device_id: str
    action: str
    payload: dict = {}
    start_time: str # ISO format expected
    end_time: str # ISO format expected
    auto_like_count: Optional[int] = 0
    playlist_automation_enabled: Optional[bool] = False
    playlist_name: Optional[str] = None
    songs_to_add: Optional[int] = 0

class UserCreate(BaseModel):
    username: str
    email: str
    password: str
    full_name: Optional[str] = None

class Token(BaseModel):
    access_token: str
    token_type: str
    expires_in: int
    expires_at: str
    session_id: str
    user: dict

def is_scheduled_payload(action: str, payload: dict) -> bool:
    if payload is None:
        return False
    if payload.get("session_mode") is True:
        return True
    source = payload.get("source")
    task = payload.get("task") if isinstance(payload.get("task"), dict) else None
    if isinstance(source, str) and source.lower() == "scheduled_session":
        return True
    if task and isinstance(task.get("source"), str) and task.get("source").lower() == "scheduled_session":
        return True
    return False

def sanitize_payload_for_scheduled(action: str, payload: dict) -> dict:
    clean = dict(payload or {})
    if is_scheduled_payload(action, clean):
        clean["auto_like_count"] = 0
    return clean

def sanitize_payload_for_playlist_automation(payload: dict) -> dict:
    clean = dict(payload or {})
    if not bool(clean.get("playlist_automation_enabled", False)):
        clean["playlist_name"] = ""
        clean["songs_to_add"] = 0
    return clean

# --- AUTH UTILS ---
def get_password_hash(password):
    return password # No hashing as requested

def verify_password(plain_password, stored_password):
    return plain_password == stored_password # Direct comparison

def create_access_token(data: dict, expires_delta: Optional[datetime.timedelta] = None):
    to_encode = data.copy()
    if expires_delta:
        expire = now_local() + expires_delta
    else:
        expire = now_local() + datetime.timedelta(minutes=15)
    
    jti = uuid.uuid4().hex
    to_encode.update({"exp": expire, "jti": jti})
    encoded_jwt = jwt.encode(to_encode, JWT_SECRET, algorithm=JWT_ALGORITHM)
    return encoded_jwt, jti, expire

# --- STATE & BROADCAST ---
async def discovery_task():
    import socket
    DISCOVERY_PORT = 8888
    
    # Get local IP reliably
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 1))
        IP = s.getsockname()[0]
    except Exception:
        IP = '127.0.0.1'
    finally:
        s.close()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    
    payload = json.dumps({
        "type": "SPOTIFY_SERVER",
        "ip": IP,
        "port": 8000
    }).encode('utf-8')
    
    log_system(f"📡 Discovery started on {IP}:{DISCOVERY_PORT} (Target: {IP}:8000)")
    
    try:
        while True:
            # Broadcast to the subnet
            sock.sendto(payload, ('<broadcast>', DISCOVERY_PORT))
            await asyncio.sleep(5)
    except asyncio.CancelledError:
        sock.close()

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup Banner
    db = SessionLocal()
    device_count = db.query(Device).filter(Device.status == "online").count()
    db.close()
    
    print_banner(
        version="1.5.0-PROD",
        db_path=SQLALCHEMY_DATABASE_URL,
        ws_endpoint="ws://0.0.0.0:8000/ws/device",
        device_count=device_count
    )
    
    # Startup: Start background tasks
    task = asyncio.create_task(sentinel_task())
    cleanup_task = asyncio.create_task(session_cleanup_task())
    scheduler_task = asyncio.create_task(session_scheduler_task())
    discovery = asyncio.create_task(discovery_task())
    
    yield
    # Shutdown: Clean up (optional)
    log_system("Backend shutting down...")
    task.cancel()
    cleanup_task.cancel()
    scheduler_task.cancel()
    discovery.cancel()

app = FastAPI(title="Spotify Automation Hub", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

active_devices = {} 
dashboards = {} # {WebSocket: user_id}
run_map = {} 

async def broadcast_to_dashboards(msg):
    # Log/Event messages usually have command_id, which we use to find run_id -> owner_id
    db = SessionLocal()
    try:
        run_id = run_map.get(msg.get("command_id"))
        owner_id = None
        if run_id:
            run = db.query(Run).filter(Run.id == run_id).first()
            if run: owner_id = run.owner_id
        
        dead = []
        for ws, user_id in dashboards.items():
            if owner_id and user_id != owner_id: continue
            try:
                await ws.send_text(json.dumps(msg))
            except:
                dead.append(ws)
        for ws in dead:
            if ws in dashboards: del dashboards[ws]
    finally:
        db.close()

async def broadcast_device_list():
    db = SessionLocal()
    try:
        all_devices = db.query(Device).all()
        dead = []
        for ws, user_id in dashboards.items():
            # Show devices owned by user OR unowned devices (waiting to be claimed)
            user_devices = [d for d in all_devices if d.owner_id == user_id or d.owner_id is None]
            try:
                await ws.send_text(json.dumps({
                    "type": "REGISTRY_UPDATE",
                    "devices": [
                        {
                            "id": d.device_id, 
                            "name": d.device_name,
                            "status": d.status, 
                            "battery": d.battery,
                            "network_type": d.network_type,
                            "last_seen": d.last_seen.isoformat() if d.last_seen else None
                        } for d in user_devices
                    ]
                }))
            except:
                dead.append(ws)
        for ws in dead:
            if ws in dashboards: del dashboards[ws]
    finally:
        db.close()

# --- DB SESSION ---
def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

# --- AUTH DEPENDENCY ---
from fastapi import Request
async def get_current_user(request: Request, db: Session = Depends(get_db)):
    auth_header = request.headers.get("Authorization")
    if not auth_header or not auth_header.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"success": False, "reason": "MISSING_TOKEN"}
        )
    
    token = auth_header.split(" ")[1]
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail={"success": False, "reason": "INVALID_TOKEN"},
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        username: str = payload.get("sub")
        jti: str = payload.get("jti")
        if username is None or jti is None:
            raise credentials_exception
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail={"success": False, "reason": "TOKEN_EXPIRED"})
    except JWTError:
        raise credentials_exception

    # Session Validation Against DB (As requested)
    session = db.query(UserSession).filter(
        UserSession.jwt_jti == jti,
        UserSession.is_active == True,
        UserSession.revoked_at == None
    ).first()

    if not session:
        raise credentials_exception
    
    # Check expiry against local time
    if session.expires_at.replace(tzinfo=LOCAL_TZ) < now_local():
        session.is_active = False
        db.commit()
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail={"success": False, "reason": "TOKEN_EXPIRED"})

    user = db.query(User).filter(User.id == session.user_id).first()
    if user is None or not user.is_active:
        raise credentials_exception
    
    return user

# --- AUTH ENDPOINTS ---
@app.post("/auth/signup")
def signup(user_in: UserCreate, db: Session = Depends(get_db)):
    try:
        # Validate duplicates
        if db.query(User).filter(User.username == user_in.username).first():
            raise HTTPException(status_code=400, detail={"success": False, "reason": "USERNAME_TAKEN"})
        if db.query(User).filter(User.email == user_in.email).first():
            raise HTTPException(status_code=400, detail={"success": False, "reason": "EMAIL_TAKEN"})
        
        # Password strength check (Simple)
        if len(user_in.password) < 6:
            raise HTTPException(status_code=400, detail={"success": False, "reason": "PASSWORD_TOO_WEAK"})

        new_user = User(
            username=user_in.username,
            email=user_in.email,
            full_name=user_in.full_name,
            password=user_in.password,
            created_at=now_local().replace(tzinfo=None)
        )
        db.add(new_user)
        db.commit()
        return {"status": "success", "message": "User created"}
    except HTTPException: raise
    except Exception as e:
        log_exception("AUTH_SIGNUP", e)
        raise HTTPException(status_code=500, detail={"success": False, "reason": "INTERNAL_SERVER_ERROR"})

@app.post("/auth/login", response_model=Token)
def login(request: Request, form_data: OAuth2PasswordRequestForm = Depends(), db: Session = Depends(get_db)):
    try:
        user = db.query(User).filter(User.username == form_data.username).first()
        if not user or user.password != form_data.password:
            log_auth_event(db, "LOGIN_FAILED", form_data.username, ip=request.client.host)
            raise HTTPException(status_code=401, detail={"success": False, "reason": "INVALID_CREDENTIALS"})
        
        if not user.is_active:
            log_auth_event(db, "LOGIN_REJECTED_INACTIVE", user.username, ip=request.client.host)
            raise HTTPException(status_code=401, detail={"success": False, "reason": "USER_INACTIVE"})

        # Generate Token
        access_token_expires = datetime.timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
        access_token, jti, expires_at = create_access_token(
            data={"sub": user.username}, expires_delta=access_token_expires
        )
        
        # Create DB session
        session_id = uuid.uuid4().hex
        new_session = UserSession(
            session_id=session_id,
            user_id=user.id,
            jwt_jti=jti,
            expires_at=expires_at.replace(tzinfo=None),
            issued_at=now_local().replace(tzinfo=None),
            is_active=True,
            ip_address=request.client.host,
            user_agent=request.headers.get("user-agent")
        )
        db.add(new_session)
        
        # Update user last login
        user.last_login = now_local().replace(tzinfo=None)
        
        db.commit()
        log_auth_event(db, "LOGIN_SUCCESS", user.username, ip=request.client.host, session_id=session_id)
        
        return {
            "access_token": access_token,
            "token_type": "bearer",
            "expires_in": ACCESS_TOKEN_EXPIRE_MINUTES * 60,
            "expires_at": expires_at.isoformat(),
            "session_id": session_id,
            "user": {"id": user.id, "username": user.username, "full_name": user.full_name}
        }
    except HTTPException: raise
    except Exception as e:
        log_exception("AUTH_LOGIN", e)
        raise HTTPException(status_code=500, detail={"success": False, "reason": "INTERNAL_SERVER_ERROR"})

@app.post("/auth/logout")
def logout(request: Request, current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    auth_header = request.headers.get("Authorization")
    token = auth_header.split(" ")[1]
    payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
    jti = payload.get("jti")
    session = db.query(UserSession).filter(UserSession.jwt_jti == jti).first()
    if session:
        session.is_active = False
        session.revoked_at = now_local().replace(tzinfo=None)
        log_auth_event(db, "LOGOUT", current_user.username, ip=request.client.host, session_id=session.session_id)
        db.commit()
    return {"success": True, "message": "Logged out"}

@app.post("/auth/logout_all")
def logout_all(current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    db.query(UserSession).filter(
        UserSession.user_id == current_user.id,
        UserSession.is_active == True
    ).update({
        "is_active": False,
        "revoked_at": now_local().replace(tzinfo=None)
    })
    log_auth_event(db, "SESSION_REVOKED_ALL", current_user.username)
    db.commit()
    return {"success": True, "message": "All sessions revoked"}

@app.get("/auth/sessions")
def list_sessions(request: Request, current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    auth_header = request.headers.get("Authorization")
    token = auth_header.split(" ")[1]
    payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
    current_jti = payload.get("jti")
    
    sessions = db.query(UserSession).filter(
        UserSession.user_id == current_user.id,
        UserSession.is_active == True
    ).order_by(UserSession.issued_at.desc()).all()
    
    return [
        {
            "session_id": s.session_id,
            "issued_at": s.issued_at.isoformat(),
            "expires_at": s.expires_at.isoformat(),
            "is_current": s.jwt_jti == current_jti,
            "ip_address": s.ip_address,
            "user_agent": s.user_agent
        } for s in sessions
    ]

@app.get("/auth/me")
def read_users_me(current_user: User = Depends(get_current_user)):
    return {
        "id": current_user.id,
        "username": current_user.username,
        "email": current_user.email,
        "full_name": current_user.full_name
    }

# --- ENDPOINTS ---
@app.get("/devices")
def list_devices(current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    # Show devices owned by current user OR unowned devices
    devices = db.query(Device).filter(
        or_(Device.owner_id == current_user.id, Device.owner_id == None)
    ).all()
    now = now_local().replace(tzinfo=None) # naive for compare
    for d in devices:
        diff = (now - d.last_seen).total_seconds()
        if diff > 60 and d.device_id not in active_devices:
            d.status = "offline"
    db.commit()
    return devices

@app.get("/tasks")
def list_tasks(current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return db.query(Task).filter(Task.owner_id == current_user.id, Task.is_system == False).order_by(Task.created_at.desc()).all()

@app.get("/runs")
def list_runs(current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return db.query(Run).filter(Run.owner_id == current_user.id).order_by(Run.start_time.desc()).limit(100).all()

@app.get("/run_events/{run_id}")
def list_run_events(run_id: str, current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    # Verify ownership of the run
    run = db.query(Run).filter(Run.id == run_id, Run.owner_id == current_user.id).first()
    if not run: raise HTTPException(status_code=404, detail="Run not found")
    return db.query(RunEvent).filter(RunEvent.run_id == run_id).order_by(RunEvent.timestamp.asc()).all()

@app.post("/sessions")
def create_session(session_in: SessionCreate, current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    try:
        # Convert ISO strings (likely UTC from frontend) to server's local timezone before saving
        # This ensures they are compatible with the scheduler's comparison logic
        start_dt = datetime.datetime.fromisoformat(session_in.start_time.replace("Z", "+00:00"))
        end_dt = datetime.datetime.fromisoformat(session_in.end_time.replace("Z", "+00:00"))
        
        start_time = start_dt.astimezone(LOCAL_TZ).replace(tzinfo=None)
        end_time = end_dt.astimezone(LOCAL_TZ).replace(tzinfo=None)
        
        payload = dict(session_in.payload or {})
        payload["playlist_automation_enabled"] = bool(session_in.playlist_automation_enabled)
        payload["session_mode"] = True
        payload["source"] = "scheduled_session"
        payload = sanitize_payload_for_scheduled(session_in.action, payload)
        payload = sanitize_payload_for_playlist_automation(payload)

        task = Task(
            id=uuid.uuid4().hex,
            task_name=f"SESSION: {session_in.action.upper()}",
            action_type=session_in.action,
            search_query=payload.get("query"),
            action_params=payload,
            is_system=False,
            status="scheduled",
            auto_like_count=0,
            playlist_name=payload.get("playlist_name"),
            songs_to_add=payload.get("songs_to_add", 0),
            owner_id=current_user.id
        )
        db.add(task)
        
        new_session = ScheduledSession(
            user_id=current_user.id,
            device_id=session_in.device_id,
            task_id=task.id,
            start_time=start_time,
            end_time=end_time
        )
        db.add(new_session)
        db.commit()
        return {"status": "success", "session_id": new_session.id}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

@app.get("/sessions")
def list_sessions(current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return db.query(ScheduledSession).filter(ScheduledSession.user_id == current_user.id).order_by(ScheduledSession.start_time.asc()).all()

@app.post("/sessions/{session_id}/stop")
async def stop_session(session_id: str, current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    session = db.query(ScheduledSession).filter(ScheduledSession.id == session_id, ScheduledSession.user_id == current_user.id).first()
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    
    session.status = "done"
    session.end_time = now_local().replace(tzinfo=None)
    db.commit()
    
    if session.device_id in active_devices:
        try:
            await active_devices[session.device_id].send_text(json.dumps({
                "type": "STOP_SESSION",
                "session_id": session.id
            }))
        except Exception: pass
        
    return {"status": "success"}

@app.post("/send_command")

async def send_command(req: CommandRequest, current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    if req.device_id not in active_devices:
        raise HTTPException(status_code=404, detail="Device not connected")
    
    # Check device ownership
    device = db.query(Device).filter(Device.device_id == req.device_id).first()
    if device and device.owner_id and device.owner_id != current_user.id:
        raise HTTPException(status_code=403, detail="Not authorized for this device")
    
    # Auto-claim device if unowned
    if device and not device.owner_id:
        device.owner_id = current_user.id
        db.commit()

    is_system = req.action in ["mode_change", "heartbeat"]
    
    req.payload = sanitize_payload_for_scheduled(req.action, req.payload or {})
    req.payload = sanitize_payload_for_playlist_automation(req.payload)
    if is_scheduled_payload(req.action, req.payload):
        req.payload["auto_like_count"] = 0

    # Hierarchical Artist Action handling
    artist_sub_action = req.payload.get("artist_action") if req.action == "artist" else None
    
    task = db.query(Task).filter(
        Task.action_type == req.action, 
        Task.search_query == req.payload.get("query"),
        Task.owner_id == current_user.id
    )
    
    if artist_sub_action:
        task = task.filter(func.json_extract(Task.action_params, '$.artist_action') == artist_sub_action)
        
    task = task.first()
    
    if not task:
        display_action = f"ARTIST ({artist_sub_action})" if artist_sub_action else req.action.upper()
        task = Task(
            id=uuid.uuid4().hex,
            task_name=f"{display_action}: {req.payload.get('query', 'N/A')}",
            action_type=req.action,
            search_query=req.payload.get("query"),
            action_params=req.payload,
            auto_like_count=req.payload.get("auto_like_count", 0),
            playlist_name=req.payload.get("playlist_name"),
            songs_to_add=req.payload.get("songs_to_add", 0),
            create_if_missing=req.payload.get("create_if_missing", True),
            is_system=is_system,
            status="running",
            owner_id=current_user.id
        )
        db.add(task); db.commit(); db.refresh(task)
    else:
        task.status = "running"
        task.action_params = req.payload
        task.auto_like_count = req.payload.get("auto_like_count", 0)
        task.playlist_name = req.payload.get("playlist_name")
        task.songs_to_add = req.payload.get("songs_to_add", 0)
        task.create_if_missing = req.payload.get("create_if_missing", True)
        db.commit()
    
    run = Run(id=uuid.uuid4().hex, task_id=task.id, device_id=req.device_id, status="running", owner_id=current_user.id)
    db.add(run); db.commit(); db.refresh(run)

    cmd_id = f"cmd_{int(time.time())}_{uuid.uuid4().hex[:4]}"
    run_map[cmd_id] = run.id

    try:
        issued_at = int(time.time() * 1000)
        ttl_ms = 300000 # 5 minutes TTL
        await active_devices[req.device_id].send_text(json.dumps({
            "type": "COMMAND", "command_id": cmd_id, "action": req.action, "payload": req.payload,
            "issued_at": issued_at, "ttl_ms": ttl_ms
        }))
        return {"status": "dispatched", "run_id": run.id, "command_id": cmd_id}
    except Exception as e:
        run.status = "failed"; run.final_reason = str(e); db.commit()
        raise HTTPException(status_code=500, detail="Failed to send to socket")

# --- BACKGROUND SENTINEL ---
import asyncio
async def sentinel_task():
    while True:
        await asyncio.sleep(30)
        db = SessionLocal()
        try:
            now = now_local().replace(tzinfo=None)
            
            # 1. Heartbeat Timeout (Device)
            # Increased to 120s for better NAT/Doze resilience
            threshold = now - datetime.timedelta(seconds=120)
            stale_devs = db.query(Device).filter(Device.status == "online", Device.last_seen < threshold).all()
            for dev in stale_devs:
                if dev.device_id not in active_devices:
                    log_device(f"⚠️ Sentinel: Device timed out (Last seen: {dev.last_seen})", dev.device_id)
                    dev.status = "offline"
                    # Do NOT fail runs; pause tracking to allow reconnects
                    # for run in db.query(Run).filter(Run.device_id == dev.device_id, Run.status == "running").all():
                    #     log_step_failed(run.id, "SENTINEL", "Device Timeout", dev.device_id)
                    #     run.status = "failed"; run.end_time = now_local(); run.final_reason = "Sentinel: Device Timeout"
            
            # 2. Activity Timeout (Runs)
            # Find runs that are 'running' but have no events in 300s (TTL)
            running_runs = db.query(Run).filter(Run.status == "running").all()
            for run in running_runs:
                last_event = db.query(RunEvent).filter(RunEvent.run_id == run.id).order_by(RunEvent.timestamp.desc()).first()
                last_time = last_event.timestamp if last_event else run.start_time
                if (now - last_time).total_seconds() > 300:
                    run.status = "failed"
                    run.end_time = now_local()
                    run.final_reason = "COMMAND_TTL_EXCEEDED"
                    task = db.query(Task).filter(Task.id == run.task_id).first()
                    if task: task.status = "failed"

            db.commit()
            if stale_devs: await broadcast_device_list()
        finally:
            db.close()

# --- AUTH FOR WEBSOCKETS ---
async def get_ws_user(websocket: WebSocket, db: Session):
    # We accept token in query params or headers
    token = websocket.query_params.get("token")
    if not token:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return None
    
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        jti = payload.get("jti")
        if not jti: return None
        
        session = db.query(UserSession).filter(UserSession.jwt_jti == jti, UserSession.is_active == True).first()
        if not session or session.expires_at.replace(tzinfo=LOCAL_TZ) < now_local():
            return None
            
        user = db.query(User).filter(User.id == session.user_id).first()
        return user if user and user.is_active else None
    except:
        return None

# --- BACKGROUND TASKS ---
async def session_scheduler_task():
    while True:
        await asyncio.sleep(10)
        db = SessionLocal()
        try:
            now = now_local().replace(tzinfo=None)
            
            # 1. Start scheduled sessions
            to_start = db.query(ScheduledSession).filter(
                ScheduledSession.status == "scheduled",
                ScheduledSession.start_time <= now,
                ScheduledSession.end_time > now
            ).all()
            
            for session in to_start:
                session.status = "running"
                db.commit()
                if session.device_id in active_devices:
                    task = db.query(Task).filter(Task.id == session.task_id).first()
                    if task:
                        cmd_id = f"cmd_{int(time.time())}_{uuid.uuid4().hex[:4]}"
                        try:
                            issued_at = int(time.time() * 1000)
                            ttl_ms = int((session.end_time - now).total_seconds() * 1000)
                            payload = dict(task.action_params or {})
                            payload = sanitize_payload_for_playlist_automation(payload)
                            payload["session_mode"] = True
                            payload["source"] = "scheduled_session"
                            payload["session_end_time"] = int(session.end_time.timestamp() * 1000)
                            payload["auto_like_count"] = 0
                            payload["playlist_name"] = task.playlist_name
                            payload["songs_to_add"] = task.songs_to_add
                            payload["create_if_missing"] = task.create_if_missing
                            
                            await active_devices[session.device_id].send_text(json.dumps({
                                "type": "COMMAND", 
                                "command_id": cmd_id, 
                                "action": task.action_type, 
                                "payload": payload,
                                "issued_at": issued_at, 
                                "ttl_ms": ttl_ms,
                                "is_session": True,
                                "session_id": session.id
                            }))
                            log_system(f"🚀 Scheduler: Started Session {session.id}")
                        except Exception as e:
                            log_system(f"❌ Scheduler: Failed to start Session {session.id}: {e}")
            
            # 2. Stop running sessions that hit end_time
            to_stop = db.query(ScheduledSession).filter(
                ScheduledSession.status == "running",
                ScheduledSession.end_time <= now
            ).all()
            
            for session in to_stop:
                session.status = "done"
                db.commit()
                if session.device_id in active_devices:
                    try:
                        await active_devices[session.device_id].send_text(json.dumps({
                            "type": "STOP_SESSION",
                            "session_id": session.id
                        }))
                        log_system(f"🛑 Scheduler: Stopped Session {session.id} (Time Expired)")
                    except Exception: pass
                    
        finally:
            db.close()

async def session_cleanup_task():
    while True:
        await asyncio.sleep(3600) # Every hour
        db = SessionLocal()
        try:
            now = now_local().replace(tzinfo=None)
            db.query(UserSession).filter(
                UserSession.is_active == True,
                UserSession.expires_at < now
            ).update({"is_active": False})
            db.commit()
            log_system("🧹 Sentinel: Expired sessions cleaned up")
        finally:
            db.close()

# --- WEBSOCKETS ---
@app.websocket("/ws/dashboard")
async def dashboard_websocket(websocket: WebSocket):
    await websocket.accept()
    
    db = SessionLocal()
    user = await get_ws_user(websocket, db)
    db.close()
    
    if not user:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    dashboards[websocket] = user.id
    await broadcast_device_list() 
    log_ws(f"New dashboard viewer: {user.username}", "DASHBOARD")
    try:
        while True: await websocket.receive_text()
    except Exception:
        pass
    finally: 
        if websocket in dashboards: del dashboards[websocket]
        log_ws(f"Dashboard viewer disconnected: {user.username}", "DASHBOARD")

@app.websocket("/ws/device")
async def device_websocket(websocket: WebSocket):
    await websocket.accept()
    device_id = None
    try:
        while True:
            data = await websocket.receive_text()
            msg = json.loads(data)
            
            db = SessionLocal()
            try:
                # Always update last_seen on any activity
                if device_id:
                    dev = db.query(Device).filter(Device.device_id == device_id).first()
                    if dev: 
                        dev.last_seen = now_local().replace(tzinfo=None)
                        dev.status = "online"
                        db.commit()

                if msg.get("type") == "HELLO":
                    device_id = msg.get("deviceId", "unknown")
                    active_devices[device_id] = websocket
                    log_ws(f"✨ Handshake OK (Name: {msg.get('deviceName')})", device_id)
                    
                    dev = db.query(Device).filter(Device.device_id == device_id).first()
                    if not dev:
                        dev = Device(
                            device_id=device_id, 
                            device_name=msg.get("deviceName"),
                            status="online", 
                            app_version=msg.get("appVersion")
                        )
                        db.add(dev)
                    else:
                        dev.status = "online"
                        dev.device_name = msg.get("deviceName")
                        dev.last_seen = now_local().replace(tzinfo=None)
                    db.commit()
                    await broadcast_device_list()

                elif msg.get("type") == "HEARTBEAT":
                    if device_id:
                        dev = db.query(Device).filter(Device.device_id == device_id).first()
                        if dev:
                            dev.last_seen = now_local().replace(tzinfo=None)
                            dev.status = "online"
                            dev.battery = msg.get("battery")
                            dev.network_type = msg.get("network_type")
                            db.commit()
                            log_device(f"💓 Heartbeat [Batt: {dev.battery}% | {dev.network_type}]", device_id)

                elif msg.get("type") in ("LOG", "EVENT"):
                    cmd_id = msg.get("command_id", "UNKNOWN")
                    run_id = run_map.get(cmd_id)
                    step = msg.get("step", "UNKNOWN")
                    status = msg.get("status", "UNKNOWN")
                    message = msg.get("message", "")

                    if run_id:
                        # Structured Logging
                        if status == "STARTED": log_step_started(run_id, step, device_id)
                        elif status == "OK": log_step_ok(run_id, step, device_id)
                        elif status == "WARNING": log_step_warning(run_id, step, message, device_id)
                        elif status == "FAILED": log_step_failed(run_id, step, message, device_id)
                    else:
                        # Unmapped event (like PREP, NAV)
                        log_device(f"📡 [DEVICE_EVENT] [{cmd_id}] {step} | {status} | {message}", device_id)

                    if run_id:
                        # Final Service Completion Logging
                        if step == "FINAL":
                            run = db.query(Run).filter(Run.id == run_id).first()
                            if run:
                                task = db.query(Task).filter(Task.id == run.task_id).first()
                                device = db.query(Device).filter(Device.device_id == device_id).first()
                                
                                action_name = task.action_type.upper() if task else "UNKNOWN_ACTION"
                                dev_name = device.device_name if device else "UNKNOWN_DEVICE"
                                task_id = task.id[:8] if task else "---"
                                
                                log_service_completion(
                                    status="SUCCESS" if status == "OK" else "FAILED",
                                    device_id=device_id,
                                    device_name=dev_name,
                                    action=action_name,
                                    task_id=f"TASK-{task_id}",
                                    run_id=f"RUN-{run_id[:8]}",
                                    reason=message if status == "FAILED" else None
                                )

                        # Persist Event
                        db.add(RunEvent(
                            id=uuid.uuid4().hex,
                            run_id=run_id, 
                            event_type=step, 
                            node=message, 
                            payload=msg,
                            timestamp=now_local().replace(tzinfo=None)
                        ))
                        
                        # Mark status on FINAL event
                        if step == "FINAL":
                            run = db.query(Run).filter(Run.id == run_id).first()
                            if run:
                                run.status = "success" if status == "OK" else "failed"
                                run.end_time = now_local().replace(tzinfo=None)
                                run.final_reason = message
                                
                                task = db.query(Task).filter(Task.id == run.task_id).first()
                                if task: task.status = "done" if run.status == "success" else "failed"
                                
                                # Clean memory map
                                if msg.get("command_id") in run_map: del run_map[msg.get("command_id")]
                        db.commit()
                    await broadcast_to_dashboards(msg)
                
                elif msg.get("type") == "SESSION_STATS":
                    session_id = msg.get("session_id")
                    if session_id:
                        session = db.query(ScheduledSession).filter(ScheduledSession.id == session_id).first()
                        if session:
                            data = msg
                            session.total_duration = data.get("total_duration", session.total_duration)
                            session.elapsed_time = data.get("elapsed_time", session.elapsed_time)
                            session.remaining_time = data.get("remaining_time", session.remaining_time)
                            session.loop_count = data.get("loop_count", session.loop_count)
                            session.playback_state = data.get("playback_state", session.playback_state)
                            session.liked_count = data.get("liked_count", session.liked_count)
                            db.commit()
                    await broadcast_to_dashboards(msg)
                
                elif msg.get("type") == "PING":
                    await websocket.send_text(json.dumps({"type": "PONG"}))

            except Exception as e:
                db.rollback()
                log_exception("WS", f"Error processing device message: {e}", device_id)
            finally:
                db.close()
                
    except WebSocketDisconnect:
        pass
    except Exception as e:
        log_exception("WS", f"Device WebSocket Error: {e}", device_id)
    finally:
        if device_id:
            if device_id in active_devices: del active_devices[device_id]
            db = SessionLocal()
            try:
                dev = db.query(Device).filter(Device.device_id == device_id).first()
                if dev: 
                    dev.status = "offline"
                    dev.last_seen = now_local().replace(tzinfo=None)
                    db.commit()
            finally:
                db.close()
            await broadcast_device_list()

@app.get("/ping")
def ping():
    return {"status": "ok", "service": "spotify-automation-hub"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
