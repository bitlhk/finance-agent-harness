"""CRUD operations for Finance Guardrail database."""
import json
import logging
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import select, func, and_, delete
from sqlalchemy.ext.asyncio import AsyncSession

from .models import (
    RequestLog, GuardrailRule, WhitelistRule, PIIConfig, PromptDefenseConfig,
    ContentFilterConfig, ContentFilterCategory, ContentFilterAnchor,
)

logger = logging.getLogger("database")


@dataclass
class SecurityStatistics:
    """Security statistics data class."""

    total_requests: int = 0
    blocked_requests: int = 0
    attack_requests: int = 0
    safe_requests: int = 0
    safety_rate: float = 100.0
    guardrails_blocked: int = 0
    llm_blocked: int = 0
    pii_blocked: int = 0
    content_filter_blocked: int = 0

    def to_dict(self) -> dict[str, Any]:
        """Convert to dictionary."""
        return {
            "total_requests": self.total_requests,
            "blocked_requests": self.blocked_requests,
            "attack_requests": self.attack_requests,
            "safe_requests": self.safe_requests,
            "safety_rate": self.safety_rate,
            "guardrails_blocked": self.guardrails_blocked,
            "llm_blocked": self.llm_blocked,
            "pii_blocked": self.pii_blocked,
            "content_filter_blocked": self.content_filter_blocked,
        }


class RequestLogCRUD:
    """CRUD operations for RequestLog model."""
    
    @staticmethod
    async def create_log(
        db: AsyncSession,
        source_ip: str | None = None,
        user_agent: str | None = None,
        is_attack: bool = False,
        is_blocked: bool = False,
        attack_type: str | None = None,
        security_risks: dict[str, Any] | None = None,
        detect_reason: str | None = None,
        detected_by: str | None = None,
        user_input: str = "",
        response_content: str | None = None,
        conversation_id: str | None = None,
        access_time: datetime | None = None,
    ) -> RequestLog:
        """
        Create a new request log entry.

        Args:
            db: Database session
            source_ip: Source IP address
            user_agent: User agent string
            is_attack: Whether the request is an attack
            is_blocked: Whether the request was blocked
            attack_type: Type of attack detected
            security_risks: Security risks detected (dict)
            detect_reason: Detection or blocking reason
            detected_by: Who detected the issue ('guardrails' or 'llm')
            user_input: User input content
            response_content: Response content
            conversation_id: Conversation ID
            access_time: Access timestamp (defaults to now)

        Returns:
            Created RequestLog instance
        """
        # Convert security_risks dict to JSON string
        security_risks_json = None
        if security_risks:
            security_risks_json = json.dumps(security_risks, ensure_ascii=False)

        # Create log entry
        log_entry = RequestLog(
            source_ip=source_ip,
            user_agent=user_agent,
            is_attack=is_attack,
            is_blocked=is_blocked,
            attack_type=attack_type,
            security_risks=security_risks_json,
            detect_reason=detect_reason,
            detected_by=detected_by,
            user_input=user_input,
            response_content=response_content,
            conversation_id=conversation_id,
            access_time=access_time or datetime.now(),
        )

        db.add(log_entry)
        await db.flush()
        await db.refresh(log_entry)

        return log_entry
    
    @staticmethod
    async def _count_logs(
        db: AsyncSession,
        *conditions,
        time_filter: list | None = None,
    ) -> int:
        """Execute a conditional count query.

        Args:
            db: Database session
            *conditions: Additional WHERE conditions
            time_filter: Optional time range filters

        Returns:
            Count result (0 if None)
        """
        query = select(func.count(RequestLog.id))
        all_conditions = list(conditions)

        if time_filter:
            all_conditions = time_filter + all_conditions

        if all_conditions:
            query = query.where(and_(*all_conditions))

        result = await db.execute(query)
        return result.scalar() or 0

    @staticmethod
    async def get_statistics(
        db: AsyncSession,
        start_time: datetime | None = None,
        end_time: datetime | None = None,
    ) -> SecurityStatistics:
        """Get security statistics.

        Args:
            db: Database session
            start_time: Start time for filtering (optional)
            end_time: End time for filtering (optional)

        Returns:
            SecurityStatistics data class instance
        """
        # Build time filters
        time_filter = []
        if start_time:
            time_filter.append(RequestLog.access_time >= start_time)
        if end_time:
            time_filter.append(RequestLog.access_time <= end_time)

        # Execute count queries
        total_requests = await RequestLogCRUD._count_logs(db, time_filter=time_filter)
        blocked_requests = await RequestLogCRUD._count_logs(
            db, RequestLog.is_blocked.is_(True), time_filter=time_filter
        )
        attack_requests = await RequestLogCRUD._count_logs(
            db, RequestLog.is_attack.is_(True), time_filter=time_filter
        )
        guardrails_blocked = await RequestLogCRUD._count_logs(
            db,
            RequestLog.is_blocked.is_(True),
            RequestLog.detected_by == "guardrails",
            time_filter=time_filter,
        )
        llm_blocked = await RequestLogCRUD._count_logs(
            db,
            RequestLog.is_blocked.is_(True),
            RequestLog.detected_by == "llm",
            time_filter=time_filter,
        )
        pii_blocked = await RequestLogCRUD._count_logs(
            db,
            RequestLog.is_blocked.is_(True),
            RequestLog.detected_by == "pii",
            time_filter=time_filter,
        )
        content_filter_blocked = await RequestLogCRUD._count_logs(
            db,
            RequestLog.is_blocked.is_(True),
            RequestLog.detected_by == "content_filter",
            time_filter=time_filter,
        )

        # Calculate derived statistics
        safe_requests = total_requests - blocked_requests
        safety_rate = 100.0
        if total_requests > 0:
            safety_rate = round((safe_requests / total_requests) * 100, 2)

        return SecurityStatistics(
            total_requests=total_requests,
            blocked_requests=blocked_requests,
            attack_requests=attack_requests,
            safe_requests=safe_requests,
            safety_rate=safety_rate,
            guardrails_blocked=guardrails_blocked,
            llm_blocked=llm_blocked,
            pii_blocked=pii_blocked,
            content_filter_blocked=content_filter_blocked,
        )
    
    @staticmethod
    async def get_attack_type_distribution(
        db: AsyncSession,
        start_time: datetime | None = None,
        end_time: datetime | None = None,
    ) -> dict[str, int]:
        """
        Get distribution of attack types.
        
        Args:
            db: Database session
            start_time: Start time for filtering (optional)
            end_time: End time for filtering (optional)
            
        Returns:
            Dictionary mapping attack types to counts
        """
        # Build query filters
        filters = [RequestLog.attack_type.isnot(None)]
        if start_time:
            filters.append(RequestLog.access_time >= start_time)
        if end_time:
            filters.append(RequestLog.access_time <= end_time)
        
        # Execute query
        result = await db.execute(
            select(
                RequestLog.attack_type,
                func.count(RequestLog.id).label('count')
            )
            .where(and_(*filters))
            .group_by(RequestLog.attack_type)
            .order_by(func.count(RequestLog.id).desc())
        )
        
        # Convert to dictionary
        distribution = {}
        for row in result:
            distribution[row.attack_type] = row.count
        
        return distribution
    
    @staticmethod
    async def get_recent_logs(
        db: AsyncSession,
        limit: int = 100,
        offset: int = 0,
    ) -> list[RequestLog]:
        """
        Get recent request logs.
        
        Args:
            db: Database session
            limit: Maximum number of logs to return
            offset: Number of logs to skip
            
        Returns:
            List of RequestLog instances
        """
        result = await db.execute(
            select(RequestLog)
            .order_by(RequestLog.access_time.desc())
            .limit(limit)
            .offset(offset)
        )
        
        return result.scalars().all()
    
    @staticmethod
    async def get_logs_by_conversation(
        db: AsyncSession,
        conversation_id: str,
    ) -> list[RequestLog]:
        """
        Get all logs for a specific conversation.
        
        Args:
            db: Database session
            conversation_id: Conversation ID
            
        Returns:
            List of RequestLog instances
        """
        result = await db.execute(
            select(RequestLog)
            .where(RequestLog.conversation_id == conversation_id)
            .order_by(RequestLog.access_time.asc())
        )
        
        return result.scalars().all()
    
    @staticmethod
    async def get_time_series_statistics(
        db: AsyncSession,
        start_time: datetime,
        end_time: datetime,
        interval_hours: int = 1,
    ) -> list[dict[str, Any]]:
        """
        Get time series statistics for a time range.
        
        Args:
            db: Database session
            start_time: Start time
            end_time: End time
            interval_hours: Interval in hours
            
        Returns:
            List of dictionaries with time and statistics
        """
        results = []
        current_time = start_time
        
        while current_time < end_time:
            next_time = current_time + timedelta(hours=interval_hours)
            
            stats = await RequestLogCRUD.get_statistics(
                db,
                start_time=current_time,
                end_time=next_time,
            )
            
            results.append({
                "time": current_time.isoformat(),
                **stats.to_dict(),
            })
            
            current_time = next_time

        return results

    @staticmethod
    async def get_statistics_for_period(
        db: AsyncSession,
        period: str = "24h"
    ) -> SecurityStatistics:
        """Get statistics for a given time period.

        Args:
            db: Database session
            period: Time period string ("24h", "7d", "30d", "all")

        Returns:
            SecurityStatistics for the period
        """
        now = datetime.now()
        period_map = {
            "24h": now - timedelta(hours=24),
            "7d": now - timedelta(days=7),
            "30d": now - timedelta(days=30),
        }
        start_time = period_map.get(period)
        return await RequestLogCRUD.get_statistics(
            db,
            start_time=start_time,
            end_time=now,
        )

    @staticmethod
    async def get_detected_by_distribution(
        db: AsyncSession,
        start_time: datetime | None = None,
        end_time: datetime | None = None,
    ) -> dict[str, int]:
        """Get distribution of detected_by sources.

        Args:
            db: Database session
            start_time: Start time for filtering (optional)
            end_time: End time for filtering (optional)

        Returns:
            Dictionary mapping detected_by to counts
        """
        filters = [RequestLog.is_blocked.is_(True)]
        if start_time:
            filters.append(RequestLog.access_time >= start_time)
        if end_time:
            filters.append(RequestLog.access_time <= end_time)

        result = await db.execute(
            select(
                RequestLog.detected_by,
                func.count(RequestLog.id).label('count')
            )
            .where(and_(*filters))
            .group_by(RequestLog.detected_by)
            .order_by(func.count(RequestLog.id).desc())
        )

        distribution = {}
        for row in result:
            key = row.detected_by or "unknown"
            distribution[key] = row.count
        return distribution

    @staticmethod
    async def get_earliest_log_time(db: AsyncSession) -> datetime | None:
        """Get the earliest access_time from request logs.

        Args:
            db: Database session

        Returns:
            Earliest datetime or None if no logs exist
        """
        result = await db.execute(select(func.min(RequestLog.access_time)))
        return result.scalar()

    @staticmethod
    async def get_filtered_logs(
        db: AsyncSession,
        limit: int = 20,
        offset: int = 0,
        is_attack: bool | None = None,
        is_blocked: bool | None = None,
        attack_type: str | None = None,
        start_time: datetime | None = None,
        end_time: datetime | None = None,
    ) -> tuple[list[RequestLog], int]:
        """Get filtered logs with pagination and total count.

        Args:
            db: Database session
            limit: Maximum number of logs to return
            offset: Number of logs to skip
            is_attack: Filter by attack status
            is_blocked: Filter by blocked status
            attack_type: Filter by attack type
            start_time: Filter logs after this time
            end_time: Filter logs before this time

        Returns:
            Tuple of (logs list, total count)
        """
        filters = []
        if is_attack is not None:
            filters.append(RequestLog.is_attack.is_(is_attack))
        if is_blocked is not None:
            filters.append(RequestLog.is_blocked.is_(is_blocked))
        if attack_type:
            filters.append(RequestLog.attack_type == attack_type)
        if start_time:
            filters.append(RequestLog.access_time >= start_time)
        if end_time:
            filters.append(RequestLog.access_time <= end_time)

        # Count total
        count_query = select(func.count(RequestLog.id))
        if filters:
            count_query = count_query.where(and_(*filters))
        total_result = await db.execute(count_query)
        total = total_result.scalar() or 0

        # Get logs
        query = select(RequestLog).order_by(RequestLog.access_time.desc())
        if filters:
            query = query.where(and_(*filters))
        query = query.limit(limit).offset(offset)
        result = await db.execute(query)
        return result.scalars().all(), total


class GuardrailRuleCRUD:
    """CRUD operations for GuardrailRule model."""

    @staticmethod
    async def initialize_system_rules(db: AsyncSession, default_rules: list[dict[str, Any]]) -> int:
        """
        Initialize system default rules in database.
        Only inserts if no system rules exist.

        Args:
            db: Database session
            default_rules: List of default rule dictionaries

        Returns:
            Number of rules inserted
        """
        # Check if system rules already exist
        result = await db.execute(
            select(func.count(GuardrailRule.id)).where(GuardrailRule.rule_type == 'system')
        )
        existing_count = result.scalar() or 0

        if existing_count > 0:
            return 0  # System rules already exist

        # Insert system rules
        inserted_count = 0
        for rule_data in default_rules:
            rule = GuardrailRule(
                name=rule_data['name'],
                description=rule_data.get('description', ''),
                rule_type='system',
                patterns=rule_data.get('patterns', []),
                response_message=rule_data.get('response_message'),
                is_active=True
            )
            db.add(rule)
            inserted_count += 1

        await db.flush()
        return inserted_count

    @staticmethod
    async def get_all_rules(db: AsyncSession, active_only: bool = False) -> list[GuardrailRule]:
        """
        Get all rules (system + custom).

        Args:
            db: Database session
            active_only: If True, only return active rules

        Returns:
            List of GuardrailRule instances
        """
        query = select(GuardrailRule).order_by(GuardrailRule.rule_type, GuardrailRule.name)

        if active_only:
            query = query.where(GuardrailRule.is_active == True)

        result = await db.execute(query)
        return result.scalars().all()

    @staticmethod
    async def get_rules_by_type(db: AsyncSession, rule_type: str) -> list[GuardrailRule]:
        """
        Get rules by type (system or custom).

        Args:
            db: Database session
            rule_type: 'system' or 'custom'

        Returns:
            List of GuardrailRule instances
        """
        result = await db.execute(
            select(GuardrailRule)
            .where(GuardrailRule.rule_type == rule_type)
            .order_by(GuardrailRule.name)
        )
        return result.scalars().all()

    @staticmethod
    async def get_rule_by_name(db: AsyncSession, name: str) -> GuardrailRule | None:
        """
        Get a rule by name.

        Args:
            db: Database session
            name: Rule name

        Returns:
            GuardrailRule instance or None
        """
        result = await db.execute(
            select(GuardrailRule).where(GuardrailRule.name == name)
        )
        return result.scalar_one_or_none()

    @staticmethod
    async def create_or_update_rule(
        db: AsyncSession,
        name: str,
        patterns: list[str],
        description: str | None = None,
        response_message: str | None = None,
        rule_type: str = 'custom'
    ) -> GuardrailRule:
        """
        Create a new rule or update existing one.

        Args:
            db: Database session
            name: Rule name
            patterns: List of regex patterns
            description: Rule description
            response_message: Custom response message when rule is matched
            rule_type: 'system' or 'custom'

        Returns:
            GuardrailRule instance
        """
        # Check if rule exists
        existing_rule = await GuardrailRuleCRUD.get_rule_by_name(db, name)

        if existing_rule:
            # Update existing rule
            existing_rule.patterns = patterns
            if description is not None:
                existing_rule.description = description
            if response_message is not None:
                existing_rule.response_message = response_message
            existing_rule.updated_at = datetime.utcnow()
            await db.flush()
            await db.refresh(existing_rule)
            return existing_rule
        else:
            # Create new rule
            new_rule = GuardrailRule(
                name=name,
                description=description or f"Custom rule: {name}",
                rule_type=rule_type,
                patterns=patterns,
                response_message=response_message,
                is_active=True
            )
            db.add(new_rule)
            await db.flush()
            await db.refresh(new_rule)
            return new_rule

    @staticmethod
    async def delete_rule(db: AsyncSession, name: str) -> bool:
        """
        Delete a rule by name.
        Cannot delete system rules.

        Args:
            db: Database session
            name: Rule name

        Returns:
            True if deleted, False if not found or is system rule
        """
        rule = await GuardrailRuleCRUD.get_rule_by_name(db, name)

        if not rule:
            return False

        if rule.rule_type == 'system':
            return False  # Cannot delete system rules

        await db.delete(rule)
        await db.flush()
        return True

    @staticmethod
    async def delete_all_custom_rules(db: AsyncSession) -> int:
        """
        Delete all custom rules (reset to default).

        Args:
            db: Database session

        Returns:
            Number of rules deleted
        """
        result = await db.execute(
            delete(GuardrailRule).where(GuardrailRule.rule_type == 'custom')
        )
        await db.flush()
        return result.rowcount

    @staticmethod
    async def reset_system_rules(db: AsyncSession, default_rules: list[dict[str, Any]]) -> int:
        """
        Reset system rules to default configuration.
        Deletes all existing system rules and re-inserts from default_rules.

        Args:
            db: Database session
            default_rules: List of default rule dictionaries

        Returns:
            Number of rules inserted
        """
        # Delete all existing system rules
        await db.execute(
            delete(GuardrailRule).where(GuardrailRule.rule_type == 'system')
        )
        await db.flush()

        # Re-insert system rules from default configuration
        inserted_count = 0
        for rule_data in default_rules:
            rule = GuardrailRule(
                name=rule_data['name'],
                description=rule_data.get('description', ''),
                rule_type='system',
                patterns=rule_data.get('patterns', []),
                response_message=rule_data.get('response_message'),
                is_active=True
            )
            db.add(rule)
            inserted_count += 1

        await db.flush()
        return inserted_count

    @staticmethod
    async def toggle_rule_status(db: AsyncSession, name: str, is_active: bool) -> GuardrailRule | None:
        """
        Toggle rule active status.

        Args:
            db: Database session
            name: Rule name
            is_active: New active status

        Returns:
            Updated GuardrailRule instance or None
        """
        rule = await GuardrailRuleCRUD.get_rule_by_name(db, name)

        if not rule:
            return None

        rule.is_active = is_active
        rule.updated_at = datetime.utcnow()
        await db.flush()
        await db.refresh(rule)
        return rule

    @staticmethod
    async def get_rules_for_agent(db: AsyncSession) -> list[dict[str, Any]]:
        """
        Get all active rules in format suitable for Agent.

        Args:
            db: Database session

        Returns:
            List of rule dictionaries
        """
        rules = await GuardrailRuleCRUD.get_all_rules(db, active_only=True)

        return [
            {
                "name": rule.name,
                "description": rule.description,
                "patterns": rule.patterns,
                "response_message": rule.response_message,
            }
            for rule in rules
        ]


class PromptDefenseConfigCRUD:
    """CRUD operations for prompt defense configuration."""

    @staticmethod
    async def get_or_create_config(db: AsyncSession, default_prompt: str = "") -> PromptDefenseConfig:
        """
        Get the prompt defense config. Creates a default config if none exists.
        Default: enabled with prompt from config.py.

        Args:
            db: Database session
            default_prompt: Default prompt content from config.py

        Returns:
            PromptDefenseConfig instance
        """
        result = await db.execute(select(PromptDefenseConfig).order_by(PromptDefenseConfig.id))
        config = result.scalar_one_or_none()

        if config is None:
            config = PromptDefenseConfig(
                enabled=True,
                prompt_content=default_prompt,
            )
            db.add(config)
            await db.flush()
            await db.refresh(config)
            logger.info("Prompt defense config created with default values")

        return config

    @staticmethod
    async def update_config(
        db: AsyncSession,
        enabled: bool | None = None,
        prompt_content: str | None = None,
    ) -> PromptDefenseConfig:
        """
        Update prompt defense configuration.

        Args:
            db: Database session
            enabled: Master switch
            prompt_content: Security check prompt content

        Returns:
            Updated PromptDefenseConfig instance
        """
        config = await PromptDefenseConfigCRUD.get_or_create_config(db)

        if enabled is not None:
            config.enabled = enabled
        if prompt_content is not None:
            config.prompt_content = prompt_content

        config.updated_at = datetime.utcnow()
        await db.flush()
        await db.refresh(config)
        return config


class PIIConfigCRUD:
    """CRUD operations for PII detection configuration."""

    @staticmethod
    async def get_or_create_config(db: AsyncSession) -> PIIConfig:
        """
        Get the PII config. Creates a default config if none exists.
        Default: disabled with empty entity lists.

        Args:
            db: Database session

        Returns:
            PIIConfig instance
        """
        result = await db.execute(select(PIIConfig).order_by(PIIConfig.id))
        config = result.scalar_one_or_none()

        if config is None:
            config = PIIConfig(
                input_enabled=False,
                output_enabled=False,
                input_entities=[],
                output_entities=[],
                use_presidio=True,
                threshold=0,
                anonymize_input=True,
                anonymize_output=True,
                input_action_mode='detect',
                output_action_mode='detect',
            )
            db.add(config)
            await db.flush()
            await db.refresh(config)
            logger.info("PII配置已创建默认值（禁用状态）")

        return config

    @staticmethod
    async def update_config(
        db: AsyncSession,
        input_enabled: bool | None = None,
        output_enabled: bool | None = None,
        input_entities: list[str] | None = None,
        output_entities: list[str] | None = None,
        use_presidio: bool | None = None,
        threshold: float | None = None,
        anonymize_input: bool | None = None,
        anonymize_output: bool | None = None,
        input_action_mode: str | None = None,
        output_action_mode: str | None = None,
    ) -> PIIConfig:
        """
        Update PII configuration.

        Args:
            db: Database session
            input_enabled: Whether input PII detection is enabled
            output_enabled: Whether output PII detection is enabled
            input_entities: Input entity list
            output_entities: Output entity list
            use_presidio: Whether to use Presidio
            threshold: Detection threshold (0.0-1.0)
            anonymize_input: Whether to anonymize input
            anonymize_output: Whether to anonymize output
            input_action_mode: Input PII action mode ('detect' or 'block')
            output_action_mode: Output PII action mode ('detect' or 'block')

        Returns:
            Updated PIIConfig instance
        """
        config = await PIIConfigCRUD.get_or_create_config(db)

        if input_enabled is not None:
            config.input_enabled = input_enabled
        if output_enabled is not None:
            config.output_enabled = output_enabled
        if input_entities is not None:
            config.input_entities = input_entities
        if output_entities is not None:
            config.output_entities = output_entities
        if use_presidio is not None:
            config.use_presidio = use_presidio
        if threshold is not None:
            config.threshold = int(threshold * 100)
        if anonymize_input is not None:
            config.anonymize_input = anonymize_input
        if anonymize_output is not None:
            config.anonymize_output = anonymize_output
        if input_action_mode is not None:
            config.input_action_mode = input_action_mode
        if output_action_mode is not None:
            config.output_action_mode = output_action_mode

        config.updated_at = datetime.utcnow()
        await db.flush()
        await db.refresh(config)
        return config


class WhitelistRuleCRUD:
    """CRUD operations for WhitelistRule model."""

    @staticmethod
    async def get_all_rules(db: AsyncSession, active_only: bool = False) -> list[WhitelistRule]:
        """Get all whitelist rules."""
        query = select(WhitelistRule).order_by(WhitelistRule.name)
        if active_only:
            query = query.where(WhitelistRule.is_active == True)
        result = await db.execute(query)
        return result.scalars().all()

    @staticmethod
    async def get_rule_by_name(db: AsyncSession, name: str) -> WhitelistRule | None:
        """Get a whitelist rule by name."""
        result = await db.execute(select(WhitelistRule).where(WhitelistRule.name == name))
        return result.scalar_one_or_none()

    @staticmethod
    async def create_or_update_rule(
        db: AsyncSession, name: str, pattern: str, description: str | None = None
    ) -> WhitelistRule:
        """Create or update a whitelist rule."""
        existing = await WhitelistRuleCRUD.get_rule_by_name(db, name)
        if existing:
            existing.pattern = pattern
            if description is not None:
                existing.description = description
            existing.updated_at = datetime.utcnow()
            await db.flush()
            await db.refresh(existing)
            return existing
        new_rule = WhitelistRule(
            name=name,
            description=description or f"白名单规则: {name}",
            pattern=pattern,
            is_active=True,
        )
        db.add(new_rule)
        await db.flush()
        await db.refresh(new_rule)
        return new_rule

    @staticmethod
    async def delete_rule(db: AsyncSession, name: str) -> bool:
        """Delete a whitelist rule."""
        rule = await WhitelistRuleCRUD.get_rule_by_name(db, name)
        if not rule:
            return False
        await db.delete(rule)
        await db.flush()
        return True

    @staticmethod
    async def delete_all_rules(db: AsyncSession) -> int:
        """Delete all whitelist rules."""
        result = await db.execute(delete(WhitelistRule))
        await db.flush()
        return result.rowcount

    @staticmethod
    async def get_patterns_for_agent(db: AsyncSession) -> list[str]:
        """Get active whitelist patterns for Agent."""
        rules = await WhitelistRuleCRUD.get_all_rules(db, active_only=True)
        return [rule.pattern for rule in rules]


class ContentFilterConfigCRUD:
    """CRUD operations for content filter configuration."""

    @staticmethod
    async def get_or_create_config(db: AsyncSession) -> ContentFilterConfig:
        """Get content filter config. Creates default if none exists."""
        result = await db.execute(select(ContentFilterConfig).order_by(ContentFilterConfig.id))
        config = result.scalar_one_or_none()

        if config is None:
            from config.config import CONTENT_FILTER_CONFIG
            defaults = CONTENT_FILTER_CONFIG
            config = ContentFilterConfig(
                input_enabled=defaults.get("input_enabled", True),
                output_enabled=defaults.get("output_enabled", True),
                action_mode=defaults.get("action_mode", "block"),
            )
            db.add(config)
            await db.flush()
            await db.refresh(config)
            logger.info("内容过滤器配置已创建默认值")

        return config

    @staticmethod
    async def update_config(
        db: AsyncSession,
        input_enabled: bool | None = None,
        output_enabled: bool | None = None,
        action_mode: str | None = None,
    ) -> ContentFilterConfig:
        """Update content filter configuration."""
        config = await ContentFilterConfigCRUD.get_or_create_config(db)

        if input_enabled is not None:
            config.input_enabled = input_enabled
        if output_enabled is not None:
            config.output_enabled = output_enabled
        if action_mode is not None:
            config.action_mode = action_mode

        config.updated_at = datetime.utcnow()
        await db.flush()
        await db.refresh(config)
        return config


class ContentFilterCategoryCRUD:
    """CRUD operations for content filter categories."""

    @staticmethod
    async def get_all_categories(db: AsyncSession, active_only: bool = False, category_type: str | None = None) -> list[ContentFilterCategory]:
        """Get all categories."""
        query = select(ContentFilterCategory).order_by(ContentFilterCategory.name)
        if active_only:
            query = query.where(ContentFilterCategory.is_active == True)
        if category_type:
            query = query.where(ContentFilterCategory.category_type == category_type)
        result = await db.execute(query)
        return result.scalars().all()

    @staticmethod
    async def get_category_by_name(db: AsyncSession, name: str) -> ContentFilterCategory | None:
        """Get category by name."""
        result = await db.execute(
            select(ContentFilterCategory).where(ContentFilterCategory.name == name)
        )
        return result.scalar_one_or_none()

    @staticmethod
    async def create_category(
        db: AsyncSession,
        name: str,
        threshold: float,
        description: str | None = None,
        category_type: str = 'custom',
        is_active: bool = True,
    ) -> ContentFilterCategory:
        """Create a new category."""
        category = ContentFilterCategory(
            name=name,
            description=description,
            category_type=category_type,
            threshold=str(threshold),
            is_active=is_active,
        )
        db.add(category)
        await db.flush()
        await db.refresh(category)
        return category

    @staticmethod
    async def update_category(
        db: AsyncSession,
        name: str,
        threshold: float | None = None,
        description: str | None = None,
        is_active: bool | None = None,
    ) -> ContentFilterCategory | None:
        """Update a category. Cannot update system category's name or type."""
        category = await ContentFilterCategoryCRUD.get_category_by_name(db, name)
        if not category:
            return None

        if threshold is not None:
            category.threshold = str(threshold)
        if description is not None:
            category.description = description
        if is_active is not None:
            category.is_active = is_active

        category.updated_at = datetime.utcnow()
        await db.flush()
        await db.refresh(category)
        return category

    @staticmethod
    async def delete_category(db: AsyncSession, name: str) -> bool:
        """Delete a category. Cannot delete system categories."""
        category = await ContentFilterCategoryCRUD.get_category_by_name(db, name)
        if not category:
            return False
        if category.category_type == 'system':
            return False  # Cannot delete system categories

        # Delete all anchors for this category
        await db.execute(
            delete(ContentFilterAnchor).where(ContentFilterAnchor.category_id == category.id)
        )

        await db.delete(category)
        await db.flush()
        return True

    @staticmethod
    async def initialize_system_categories(
        db: AsyncSession,
        default_categories: dict[str, dict[str, Any]],
    ) -> int:
        """Initialize system categories from default configuration.
        Only inserts if no system categories exist.
        """
        result = await db.execute(
            select(func.count(ContentFilterCategory.id)).where(ContentFilterCategory.category_type == 'system')
        )
        existing_count = result.scalar() or 0

        if existing_count > 0:
            return 0

        inserted_count = 0
        for cat_name, cat_data in default_categories.items():
            category = ContentFilterCategory(
                name=cat_name,
                description=cat_data.get('description', ''),
                category_type='system',
                threshold=str(cat_data.get('threshold', 0.75)),
                is_active=True,
            )
            db.add(category)
            inserted_count += 1

        await db.flush()
        return inserted_count

    @staticmethod
    async def reset_system_categories(
        db: AsyncSession,
        default_categories: dict[str, dict[str, Any]],
    ) -> int:
        """Reset system categories to default configuration.
        Deletes all existing system categories and anchors, then re-inserts.
        """
        # Get system category IDs
        result = await db.execute(
            select(ContentFilterCategory.id).where(ContentFilterCategory.category_type == 'system')
        )
        system_ids = [row[0] for row in result.all()]

        # Delete all anchors for system categories
        if system_ids:
            await db.execute(
                delete(ContentFilterAnchor).where(ContentFilterAnchor.category_id.in_(system_ids))
            )

        # Delete all system categories
        await db.execute(
            delete(ContentFilterCategory).where(ContentFilterCategory.category_type == 'system')
        )
        await db.flush()

        # Re-insert system categories
        inserted_count = 0
        for cat_name, cat_data in default_categories.items():
            category = ContentFilterCategory(
                name=cat_name,
                description=cat_data.get('description', ''),
                category_type='system',
                threshold=str(cat_data.get('threshold', 0.75)),
                is_active=True,
            )
            db.add(category)
            inserted_count += 1

        await db.flush()
        return inserted_count


class ContentFilterAnchorCRUD:
    """CRUD operations for content filter anchors."""

    @staticmethod
    async def get_anchors_by_category(db: AsyncSession, category_id: int, active_only: bool = False) -> list[ContentFilterAnchor]:
        """Get all anchors for a category."""
        query = select(ContentFilterAnchor).where(ContentFilterAnchor.category_id == category_id)
        if active_only:
            query = query.where(ContentFilterAnchor.is_active == True)
        query = query.order_by(ContentFilterAnchor.id)
        result = await db.execute(query)
        return result.scalars().all()

    @staticmethod
    async def get_anchors_without_embedding(db: AsyncSession) -> list[ContentFilterAnchor]:
        """Get all anchors that need vectorization (embedding is None)."""
        from sqlalchemy import or_, text

        # SQLite JSON column stores Python None as JSON text 'null' instead of SQL NULL.
        # We need to check both conditions.
        result = await db.execute(
            select(ContentFilterAnchor).where(
                or_(
                    ContentFilterAnchor.embedding.is_(None),
                    text("content_filter_anchors.embedding = 'null'"),
                )
            )
        )
        return result.scalars().all()

    @staticmethod
    async def create_anchor(
        db: AsyncSession,
        category_id: int,
        text: str,
        embedding: list[float] | None = None,
        is_active: bool = True,
    ) -> ContentFilterAnchor:
        """Create a new anchor."""
        anchor = ContentFilterAnchor(
            category_id=category_id,
            text=text,
            embedding=embedding,
            is_active=is_active,
        )
        db.add(anchor)
        await db.flush()
        await db.refresh(anchor)
        return anchor

    @staticmethod
    async def update_anchor_embedding(db: AsyncSession, anchor_id: int, embedding: list[float]) -> ContentFilterAnchor | None:
        """Update anchor embedding."""
        result = await db.execute(
            select(ContentFilterAnchor).where(ContentFilterAnchor.id == anchor_id)
        )
        anchor = result.scalar_one_or_none()
        if not anchor:
            return None

        anchor.embedding = embedding
        anchor.updated_at = datetime.utcnow()
        await db.flush()
        await db.refresh(anchor)
        return anchor

    @staticmethod
    async def delete_anchors_by_category(db: AsyncSession, category_id: int) -> int:
        """Delete all anchors for a category."""
        result = await db.execute(
            delete(ContentFilterAnchor).where(ContentFilterAnchor.category_id == category_id)
        )
        await db.flush()
        return result.rowcount

    @staticmethod
    async def reset_all_embeddings(db: AsyncSession) -> int:
        """Reset all anchor embeddings to NULL (force re-vectorization)."""
        from sqlalchemy import update
        result = await db.execute(
            update(ContentFilterAnchor).values(embedding=None)
        )
        await db.flush()
        return result.rowcount

    @staticmethod
    async def initialize_system_anchors(
        db: AsyncSession,
        default_anchors: dict[str, list[str]],
    ) -> int:
        """Initialize system anchors from default configuration.
        Only inserts if no anchors exist for system categories.
        """
        result = await db.execute(select(func.count(ContentFilterAnchor.id)))
        existing_count = result.scalar() or 0

        if existing_count > 0:
            return 0

        inserted_count = 0
        for cat_name, anchors in default_anchors.items():
            # Get category ID
            cat_result = await db.execute(
                select(ContentFilterCategory.id).where(ContentFilterCategory.name == cat_name)
            )
            category_id = cat_result.scalar_one_or_none()
            if not category_id:
                continue

            for text in anchors:
                anchor = ContentFilterAnchor(
                    category_id=category_id,
                    text=text,
                    is_active=True,
                )
                db.add(anchor)
                inserted_count += 1

        await db.flush()
        return inserted_count
