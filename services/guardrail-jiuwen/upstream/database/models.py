"""Database models for Finance Guardrail system."""
from datetime import datetime
from sqlalchemy import Column, Integer, String, DateTime, Text, Boolean, Index, JSON
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    """Base class for all database models."""
    pass


class RequestLog(Base):
    """Model for storing request logs and security information."""

    __tablename__ = "request_logs"

    id = Column(Integer, primary_key=True, autoincrement=True)

    # Request source information
    source_ip = Column(String(50), nullable=True, comment="Source IP address")
    user_agent = Column(String(255), nullable=True, comment="User agent string")

    # Request classification
    is_attack = Column(Boolean, nullable=False, default=False, comment="Whether the request is an attack")
    is_blocked = Column(Boolean, nullable=False, default=False, comment="Whether the request was blocked")

    # Security information
    attack_type = Column(String(100), nullable=True, comment="Type of attack detected")
    security_risks = Column(Text, nullable=True, comment="Security risks detected (JSON format)")
    detect_reason = Column(Text, nullable=True, comment="Detection or blocking reason")
    detected_by = Column(String(50), nullable=True, comment="Detected by: 'guardrails', 'llm', 'content_filter', or 'pii'")

    # User input
    user_input = Column(Text, nullable=False, comment="User input content")

    # Response information
    response_content = Column(Text, nullable=True, comment="Response content")

    # Timestamp
    access_time = Column(DateTime, nullable=False, default=datetime.now, comment="Access timestamp")

    # Conversation tracking
    conversation_id = Column(String(100), nullable=True, index=True, comment="Conversation ID")

    # Indexes for efficient querying
    __table_args__ = (
        Index('idx_access_time', 'access_time'),
        Index('idx_is_attack', 'is_attack'),
        Index('idx_is_blocked', 'is_blocked'),
        Index('idx_detected_by', 'detected_by'),
        Index('idx_conversation_access', 'conversation_id', 'access_time'),
    )

    def __repr__(self):
        return (
            f"<RequestLog(id={self.id}, "
            f"source_ip={self.source_ip}, "
            f"is_attack={self.is_attack}, "
            f"is_blocked={self.is_blocked}, "
            f"access_time={self.access_time})>"
        )

    def to_dict(self):
        """Convert model to dictionary."""
        return {
            "id": self.id,
            "source_ip": self.source_ip,
            "user_agent": self.user_agent,
            "is_attack": self.is_attack,
            "is_blocked": self.is_blocked,
            "attack_type": self.attack_type,
            "security_risks": self.security_risks,
            "detect_reason": self.detect_reason,
            "detected_by": self.detected_by,
            "user_input": self.user_input,
            "response_content": self.response_content,
            "access_time": self.access_time.isoformat() if self.access_time else None,
            "conversation_id": self.conversation_id,
        }


class PIIConfig(Base):
    """Model for storing PII detection configuration."""

    __tablename__ = "pii_config"

    id = Column(Integer, primary_key=True, autoincrement=True)

    input_enabled = Column(Boolean, nullable=False, default=False, comment="Whether input PII detection is enabled")
    output_enabled = Column(Boolean, nullable=False, default=False, comment="Whether output PII detection is enabled")

    # Entity configuration
    input_entities = Column(JSON, nullable=False, default=list, comment="Entities to detect in input")
    output_entities = Column(JSON, nullable=False, default=list, comment="Entities to detect in output")

    # Detection settings
    use_presidio = Column(Boolean, nullable=False, default=True, comment="Whether to use Presidio")
    threshold = Column(Integer, nullable=False, default=0, comment="Detection threshold * 100 (0-100)")
    anonymize_input = Column(Boolean, nullable=False, default=True, comment="Whether to anonymize input")
    anonymize_output = Column(Boolean, nullable=False, default=True, comment="Whether to anonymize output")

    input_action_mode = Column(String(20), nullable=False, default='detect', comment="Input PII action mode: detect or block")
    output_action_mode = Column(String(20), nullable=False, default='detect', comment="Output PII action mode: detect or block")

    # Timestamps
    created_at = Column(DateTime, nullable=False, default=datetime.utcnow, comment="Creation timestamp")
    updated_at = Column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow, comment="Last update timestamp")

    def to_dict(self):
        """Convert model to dictionary."""
        return {
            "id": self.id,
            "input_enabled": self.input_enabled,
            "output_enabled": self.output_enabled,
            "input_entities": self.input_entities or [],
            "output_entities": self.output_entities or [],
            "use_presidio": self.use_presidio,
            "threshold": self.threshold / 100.0,
            "anonymize_input": self.anonymize_input,
            "anonymize_output": self.anonymize_output,
            "input_action_mode": self.input_action_mode,
            "output_action_mode": self.output_action_mode,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }


class PromptDefenseConfig(Base):
    """Model for storing prompt defense configuration."""

    __tablename__ = "prompt_defense_config"

    id = Column(Integer, primary_key=True, autoincrement=True)

    # Master switch
    enabled = Column(Boolean, nullable=False, default=True, comment="Whether prompt defense is enabled")

    # Prompt content
    prompt_content = Column(Text, nullable=False, comment="Security check prompt content")

    # Timestamps
    created_at = Column(DateTime, nullable=False, default=datetime.utcnow, comment="Creation timestamp")
    updated_at = Column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow, comment="Last update timestamp")

    def to_dict(self):
        """Convert model to dictionary."""
        return {
            "id": self.id,
            "enabled": self.enabled,
            "prompt_content": self.prompt_content,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }


class WhitelistRule(Base):
    """Model for storing whitelist bypass rules."""

    __tablename__ = "whitelist_rules"

    id = Column(Integer, primary_key=True, autoincrement=True)

    # Rule identification
    name = Column(String(100), unique=True, nullable=False, index=True, comment="Unique rule name")
    description = Column(Text, nullable=True, comment="Rule description")

    # Single regex pattern for whitelist matching
    pattern = Column(String(500), nullable=False, comment="Regex pattern")

    # Rule status
    is_active = Column(Boolean, nullable=False, default=True, comment="Whether the rule is active")

    # Timestamps
    created_at = Column(DateTime, nullable=False, default=datetime.utcnow, comment="Creation timestamp")
    updated_at = Column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow, comment="Last update timestamp")

    # Indexes for efficient querying
    __table_args__ = (
        Index('idx_whitelist_active', 'is_active'),
    )

    def __repr__(self):
        return (
            f"<WhitelistRule(id={self.id}, "
            f"name={self.name}, "
            f"pattern={self.pattern}, "
            f"is_active={self.is_active})>"
        )

    def to_dict(self):
        """Convert model to dictionary."""
        return {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "pattern": self.pattern,
            "is_active": self.is_active,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }


class GuardrailRule(Base):
    """Model for storing guardrail security rules."""

    __tablename__ = "guardrail_rules"

    id = Column(Integer, primary_key=True, autoincrement=True)

    # Rule identification
    name = Column(String(100), unique=True, nullable=False, index=True, comment="Unique rule name")
    description = Column(Text, nullable=True, comment="Rule description")

    # Rule type: 'system' for default rules, 'custom' for user-defined rules
    rule_type = Column(String(20), nullable=False, default='custom', index=True, comment="Rule type: system or custom")

    # Rule patterns (stored as JSON array)
    patterns = Column(JSON, nullable=False, comment="List of regex patterns")

    # Custom response message when rule is matched (optional)
    response_message = Column(Text, nullable=True, comment="Custom response message when rule is matched")

    # Rule status
    is_active = Column(Boolean, nullable=False, default=True, comment="Whether the rule is active")

    # Timestamps
    created_at = Column(DateTime, nullable=False, default=datetime.utcnow, comment="Creation timestamp")
    updated_at = Column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow, comment="Last update timestamp")

    # Indexes for efficient querying
    __table_args__ = (
        Index('idx_rule_type', 'rule_type'),
        Index('idx_is_active', 'is_active'),
        Index('idx_rule_name_type', 'name', 'rule_type'),
    )

    def __repr__(self):
        return (
            f"<GuardrailRule(id={self.id}, "
            f"name={self.name}, "
            f"rule_type={self.rule_type}, "
            f"is_active={self.is_active}, "
            f"patterns_count={len(self.patterns) if self.patterns else 0})>"
        )

    def to_dict(self):
        """Convert model to dictionary."""
        return {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "rule_type": self.rule_type,
            "patterns": self.patterns or [],
            "response_message": self.response_message,
            "is_active": self.is_active,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }


class ContentFilterConfig(Base):
    """Model for storing content filter configuration."""

    __tablename__ = "content_filter_config"

    id = Column(Integer, primary_key=True, autoincrement=True)

    # Input/Output switches
    input_enabled = Column(Boolean, nullable=False, default=True, comment="Whether input content filter is enabled")
    output_enabled = Column(Boolean, nullable=False, default=True, comment="Whether output content filter is enabled")

    # Action mode: "block" or "detect"
    action_mode = Column(String(20), nullable=False, default='block', comment="Action mode: block or detect")

    # Timestamps
    created_at = Column(DateTime, nullable=False, default=datetime.utcnow, comment="Creation timestamp")
    updated_at = Column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow, comment="Last update timestamp")

    def to_dict(self):
        """Convert model to dictionary."""
        return {
            "id": self.id,
            "input_enabled": self.input_enabled,
            "output_enabled": self.output_enabled,
            "action_mode": self.action_mode,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }


class ContentFilterCategory(Base):
    """Model for storing content filter categories."""

    __tablename__ = "content_filter_categories"

    id = Column(Integer, primary_key=True, autoincrement=True)

    # Category name (e.g., 'hate', 'insult')
    name = Column(String(50), unique=True, nullable=False, index=True, comment="Category name")
    description = Column(Text, nullable=True, comment="Category description")

    # Category type: 'system' for default, 'custom' for user-defined
    category_type = Column(String(20), nullable=False, default='custom', index=True, comment="Category type: system or custom")

    # Threshold for this category
    threshold = Column(String(20), nullable=False, default='0.75', comment="Similarity threshold")

    # Whether this category is active
    is_active = Column(Boolean, nullable=False, default=True, comment="Whether the category is active")

    # Timestamps
    created_at = Column(DateTime, nullable=False, default=datetime.utcnow, comment="Creation timestamp")
    updated_at = Column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow, comment="Last update timestamp")

    # Indexes
    __table_args__ = (
        Index('idx_cf_category_type', 'category_type'),
        Index('idx_cf_category_active', 'is_active'),
        Index('idx_cf_category_name_type', 'name', 'category_type'),
    )

    def to_dict(self):
        """Convert model to dictionary."""
        return {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "category_type": self.category_type,
            "threshold": self.threshold,
            "is_active": self.is_active,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }


class ContentFilterAnchor(Base):
    """Model for storing content filter anchor texts and their embeddings."""

    __tablename__ = "content_filter_anchors"

    id = Column(Integer, primary_key=True, autoincrement=True)

    # Foreign key to category
    category_id = Column(Integer, nullable=False, index=True, comment="Category ID")

    # Anchor text
    text = Column(Text, nullable=False, comment="Anchor text")

    # Embedding vector (JSON array of floats), None if not yet vectorized
    embedding = Column(JSON, nullable=True, comment="Embedding vector (JSON array of floats)")

    # Whether this anchor is active
    is_active = Column(Boolean, nullable=False, default=True, comment="Whether the anchor is active")

    # Timestamps
    created_at = Column(DateTime, nullable=False, default=datetime.utcnow, comment="Creation timestamp")
    updated_at = Column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow, comment="Last update timestamp")

    # Indexes
    __table_args__ = (
        Index('idx_cf_anchor_category', 'category_id'),
        Index('idx_cf_anchor_active', 'is_active'),
    )

    def to_dict(self):
        """Convert model to dictionary."""
        return {
            "id": self.id,
            "category_id": self.category_id,
            "text": self.text,
            "has_embedding": self.embedding is not None,
            "is_active": self.is_active,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }


