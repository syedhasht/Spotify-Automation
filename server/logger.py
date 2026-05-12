import logging
import logging.handlers
import datetime
import pytz
import os
import sys
import re
from typing import Optional

# --- CONFIGURATION ---
LOG_FILE = "automation.logs"
LOCAL_TZ = pytz.timezone("Asia/Karachi")

# --- COLOR CONSTANTS ---
class Colors:
    RESET = "\033[0m"
    BOLD = "\033[1m"
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    RED = "\033[31m"
    CYAN = "\033[36m"
    MAGENTA = "\033[35m"
    BLUE = "\033[34m"
    WHITE = "\033[37m"

LEVEL_MAP = {
    "INFO": "INFO",
    "WARNING": "WARN",
    "ERROR": "ERR ",
    "CRITICAL": "CRIT"
}

LEVEL_COLORS = {
    "INFO": Colors.GREEN,
    "WARN": Colors.YELLOW,
    "ERR ": Colors.RED,
    "CRIT": Colors.RED + Colors.BOLD
}

def strip_ansi(text: str) -> str:
    ansi_escape = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')
    return ansi_escape.sub('', text)

class CompactFormatter(logging.Formatter):
    def __init__(self, use_color: bool = False):
        super().__init__()
        self.use_color = use_color

    def formatTime(self, record, datefmt=None):
        dt = datetime.datetime.fromtimestamp(record.created, tz=LOCAL_TZ)
        return dt.strftime("%H:%M:%S")

    def format(self, record):
        timestamp = self.formatTime(record)
        level = LEVEL_MAP.get(record.levelname, record.levelname[:4])
        category = getattr(record, "category", "SYS")[:4].upper()
        device = getattr(record, "device", "")
        run_id = getattr(record, "run_id", "")
        
        # Clean up identifiers
        device = "" if device in ["---", "unknown", "DASHBOARD"] else device
        run_id = "" if run_id == "---" else run_id
        
        # Construct identity string
        identity = ""
        if device and run_id: identity = f"{device} {run_id} "
        elif device: identity = f"{device} "
        elif run_id: identity = f"{run_id} "
        
        message = record.getMessage()
        
        if self.use_color:
            lvl_c = LEVEL_COLORS.get(level, Colors.WHITE)
            res = Colors.RESET
            timestamp = f"{Colors.WHITE}{timestamp}{res}"
            level = f"{lvl_c}{level}{res}"
            category = f"{Colors.CYAN}{category}{res}"
            identity = f"{Colors.MAGENTA}{identity}{res}" if identity else ""

        # Format: [HH:MM:SS] LEVEL CAT IDENTITY :: Message
        line = f"[{timestamp}] {level} {category} {identity}:: {message}"
        
        if record.exc_info:
            line += "\n" + self.formatException(record.exc_info)
            
        return line

# --- LOGGER SETUP ---
logger = logging.getLogger("SpotifyAutomation")
logger.setLevel(logging.DEBUG)

# 1. Plain Text File Handler (Infinite Append)
file_handler = logging.FileHandler(LOG_FILE, mode="a", encoding="utf-8")
file_handler.setFormatter(CompactFormatter(use_color=False))
logger.addHandler(file_handler)

# 2. Colored Console Handler
console_handler = logging.StreamHandler(sys.stdout)
console_handler.setFormatter(CompactFormatter(use_color=True))
logger.addHandler(console_handler)

# --- HELPER METHODS ---
def log(level: str, category: str, message: str, device: str = "---", run_id: str = "---", exc_info=None):
    extra = {"category": category, "device": device, "run_id": run_id}
    lvl = getattr(logging, level.upper(), logging.INFO)
    logger.log(lvl, message, extra=extra, exc_info=exc_info)

def log_system(msg: str): log("INFO", "SYS", msg)
def log_ws(msg: str, device: str = "---"): log("INFO", "WS", msg, device=device)
def log_device(msg: str, device: str = "---"): log("INFO", "DEV", msg, device=device)
def log_db(msg: str): log("INFO", "DB", msg)
def log_api(msg: str): log("INFO", "API", msg)

def log_step_started(run_id: str, step_name: str, device: str = "---"):
    log("INFO", "STEP", f"STARTED: {step_name}", device=device, run_id=run_id)

def log_step_ok(run_id: str, step_name: str, device: str = "---"):
    log("INFO", "OK", f"OK: {step_name}", device=device, run_id=run_id)

def log_step_warning(run_id: str, step_name: str, message: str, device: str = "---"):
    log("WARNING", "WARN", f"WARN: {step_name} | {message}", device=device, run_id=run_id)

def log_step_failed(run_id: str, step_name: str, reason: str, device: str = "---"):
    log("ERROR", "FAIL", f"FAILED: {step_name} | {reason}", device=device, run_id=run_id)

def log_command_done(run_id: str, status: str, device: str = "---"):
    level = "INFO" if status == "SUCCESS" else "ERROR"
    log(level, "DONE", f"COMMAND_DONE: {status}", device=device, run_id=run_id)

def log_exception(category: str, message: str, device: str = "---", run_id: str = "---"):
    log("ERROR", category, message, device=device, run_id=run_id, exc_info=True)

def log_service_completion(status: str, device_id: str, device_name: str, action: str, task_id: str, run_id: str, reason: str = None):
    """
    Standardized Service Completion Log
    Format: [YYYY-MM-DD HH:MM:SS.SSS TZ] [SERVICE] [SUCCESS/FAILED] [device_id_last4|device_name] action_name [reason=...] task=TASK_ID run=RUN_ID
    """
    now = datetime.datetime.now(LOCAL_TZ)
    timestamp = now.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    tz_name = now.strftime("%Z")
    
    last4 = device_id[-4:] if len(device_id) >= 4 else device_id
    dev_name = device_name or "UNKNOWN_DEVICE"
    
    reason_str = f" reason={reason}" if reason else ""
    
    # We write directly to the file and console to bypass standard formatting for this specific requirement
    log_line = f"[{timestamp} {tz_name}] [SERVICE] [{status}] [{last4}|{dev_name}] {action}{reason_str} task={task_id} run={run_id}"
    
    # 1. Write to automation.logs
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(log_line + "\n")
        f.flush()
        os.fsync(f.fileno())
    
    # 2. Write to Console (Colored for visibility)
    color = Colors.GREEN if status == "SUCCESS" else Colors.RED
    print(f"{color}{Colors.BOLD}{log_line}{Colors.RESET}")
    sys.stdout.flush()

def print_banner(version: str, db_path: str, ws_endpoint: str, device_count: int):
    print(f"\n{Colors.CYAN}{Colors.BOLD}SPOTIFY AUTOMATION PROJECT v{version} | {LOCAL_TZ} | {device_count} Nodes{Colors.RESET}\n")
    log_system(f"Backend started (v{version})")
