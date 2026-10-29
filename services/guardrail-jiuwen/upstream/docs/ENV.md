# Environment Variables

<!-- AUTO-GENERATED: Generated from config/config.py on 2026-04-29 -->

This document describes all environment variables used by the Finance Guardrail service.

## Required Variables

| Variable | Required | Description | Example |
|----------|----------|-------------|---------|
| `OPENAI_API_KEY` | **Yes** | OpenAI-compatible API key for LLM access | `sk-xxxxxxxxxxxxxxxx` |

## Optional Variables

| Variable | Default | Description | Valid Values |
|----------|---------|-------------|--------------|
| `OPENAI_BASE_URL` | `https://api.siliconflow.cn/v1/` | Base URL for OpenAI-compatible API | Any valid HTTP(S) URL |
| `OPENAI_MODEL` | `Qwen/Qwen3-8B` | LLM model identifier | `gpt-3.5-turbo`, `Qwen/Qwen3-8B` |
| `HOST` | `0.0.0.0` | Server bind address | IP address or hostname |
| `PORT` | `80` | Server listen port | `8000`, `8080`, `80` |
| `DEBUG` | `False` | Enable debug mode | `True`, `False` |
| `RELOAD` | `False` | Enable auto-reload on code changes | `True`, `False` |

## Configuration Notes

- **Production**: Set `DEBUG=False` and `RELOAD=False`
- **Development**: Set `DEBUG=True` and `RELOAD=True`
- The service uses `python-dotenv` to load `.env` file automatically if present
- Default values are hardcoded in `config/config.py` and used when environment variables are not set

## Database

The service uses SQLite via SQLAlchemy (async with `aiosqlite`). No separate database URL configuration is required; the database file is created automatically at `data/finance_guardrail.db`.

<!-- END AUTO-GENERATED -->
