# Runbook

<!-- AUTO-GENERATED: Generated from server.py, config/config.py on 2026-04-29 -->

## Deployment Procedures

### Development Deployment

```bash
uvicorn server:app --reload --host 0.0.0.0 --port 8000
```

### Production Deployment

```bash
# Option 1: Uvicorn with multiple workers
uvicorn server:app --host 0.0.0.0 --port 8000 --workers 4

# Option 2: Gunicorn with Uvicorn workers (recommended)
gunicorn -w 4 -k uvicorn.workers.UvicornWorker server:app
```

### Environment Variables for Production

```bash
export OPENAI_API_KEY=your-production-key
export DEBUG=False
export RELOAD=False
export HOST=0.0.0.0
export PORT=80
```

## Health Check Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `GET /health` | Health | Service health status |
| `GET /` | Info | Service info and available endpoints |

### Expected Health Response

```json
{
  "status": "healthy",
  "timestamp": "2026-04-29T10:00:00",
  "agent_status": "initialized"
}
```

## Monitoring

### Log Files

- Application logs: `logs/agent_service.log`
- Log rotation: 10MB per file, 5 backups retained
- Security events logged to the `security` logger

### Key Metrics

- Total requests
- Blocked requests (by guardrails, llm, pii)
- Attack detection rate
- Safety rate percentage

Access via `GET /statistics` or `GET /dashboard/statistics?period=24h`

### Dashboard Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `GET /dashboard/statistics?period=24h` | Statistics | Security stats with time filtering |
| `GET /dashboard/attack-types?period=24h` | Analytics | Attack type distribution |
| `GET /dashboard/blocked-by?period=24h` | Analytics | Blocked-by source distribution |
| `GET /dashboard/trends?period=24h` | Analytics | Time-series trends |
| `GET /dashboard/logs?limit=20` | Logs | Paginated, filtered request logs |

Period options: `24h`, `7d`, `30d`, `all`

## Common Issues and Fixes

### Service Won't Start

1. Check Python version (3.10+ required)
2. Verify all dependencies installed: `pip install -r requirements.txt`
3. Check port availability: `lsof -i :8000`
4. Verify API key is set in environment

### OpenAI API Errors

1. Verify `OPENAI_API_KEY` is correct
2. Check `OPENAI_BASE_URL` connectivity
3. Confirm account/credit balance

### Frontend Can't Connect

1. Verify backend is running
2. Check CORS configuration in `server.py`
3. Check browser console for errors

### Database Issues

- SQLite database auto-creates at `data/finance_guardrail.db`
- If corruption occurs, delete the file and restart (data will be lost)

## Rollback Procedures

1. Stop the service
2. Restore previous code version from git
3. Restart the service
4. Verify health endpoint responds correctly

## Alerting

No automated alerting is configured by default. Monitor:

- `logs/agent_service.log` for ERROR level messages
- `GET /health` endpoint for `agent_status` != `initialized`
- Rapid increase in `blocked_requests` in `/statistics`

<!-- END AUTO-GENERATED -->
