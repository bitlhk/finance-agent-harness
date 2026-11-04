"""Database connection management for Finance Guardrail system."""
import os
from contextlib import asynccontextmanager
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    create_async_engine,
    async_sessionmaker,
)
from sqlalchemy import text
from .models import Base


# Database configuration
DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "sqlite+aiosqlite:///./data/finance_guardrail.db"
)

# Create async engine
engine = create_async_engine(
    DATABASE_URL,
    echo=False,  # Set to True for SQL debugging
    future=True,
)

# Create async session factory
async_session_factory = async_sessionmaker(
    engine,
    class_=AsyncSession,
    expire_on_commit=False,
)


async def init_db():
    """Initialize database and create all tables."""
    async with engine.begin() as conn:
        # Create all tables
        await conn.run_sync(Base.metadata.create_all)

    # Run schema migrations for existing tables
    await _run_migrations()


async def _run_migrations():
    """Run lightweight schema migrations for existing SQLite databases."""
    from sqlalchemy import inspect

    async with engine.begin() as conn:
        def check_and_add_columns(sync_conn):
            inspector = inspect(sync_conn)
            tables = inspector.get_table_names()

            if "pii_config" in tables:
                columns = {col["name"] for col in inspector.get_columns("pii_config")}
                if "input_action_mode" not in columns:
                    sync_conn.execute(
                        text("ALTER TABLE pii_config ADD COLUMN input_action_mode VARCHAR(20) NOT NULL DEFAULT 'detect'")
                    )
                    print("Migration: added input_action_mode to pii_config")
                if "output_action_mode" not in columns:
                    sync_conn.execute(
                        text("ALTER TABLE pii_config ADD COLUMN output_action_mode VARCHAR(20) NOT NULL DEFAULT 'detect'")
                    )
                    print("Migration: added output_action_mode to pii_config")
                if "input_enabled" not in columns:
                    sync_conn.execute(
                        text("ALTER TABLE pii_config ADD COLUMN input_enabled BOOLEAN NOT NULL DEFAULT 0")
                    )
                    print("Migration: added input_enabled to pii_config")
                if "output_enabled" not in columns:
                    sync_conn.execute(
                        text("ALTER TABLE pii_config ADD COLUMN output_enabled BOOLEAN NOT NULL DEFAULT 0")
                    )
                    print("Migration: added output_enabled to pii_config")
                if "enabled" in columns:
                    sync_conn.execute(
                        text("ALTER TABLE pii_config DROP COLUMN enabled")
                    )
                    print("Migration: dropped deprecated 'enabled' column from pii_config")
                if "action_mode" in columns:
                    sync_conn.execute(
                        text("ALTER TABLE pii_config DROP COLUMN action_mode")
                    )
                    print("Migration: dropped deprecated 'action_mode' column from pii_config")

        await conn.run_sync(check_and_add_columns)


async def close_db():
    """Close database connection."""
    await engine.dispose()
    print("Database connection closed")


async def get_db():
    """
    Get database session for dependency injection.
    
    This is an async generator function that yields a database session
    and handles commit/rollback automatically.
    
    Usage:
        @app.post("/endpoint")
        async def endpoint(db: AsyncSession = Depends(get_db)):
            # Use db here
            pass
    """
    async with async_session_factory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
