from dataclasses import replace

from live_activation_stage19 import (
    LiveActivationStage19Request,
    evaluate_live_activation_stage19,
)


def valid_request() -> LiveActivationStage19Request:
    return LiveActivationStage19Request(
        stage18_guard_passed=True,
        symbol="BTCUSDT",
        quote_asset="USDT",
        approved_max_order_usdt="10",
        explicit_operator_confirmation=True,
        current_trading_mode="paper",
        current_live_trading_enabled="false",
        read_permission_enabled=True,
        trade_permission_enabled=True,
        withdrawal_permission_enabled=False,
        exact_ip_allowlist=True,
        leverage_enabled=False,
        margin_enabled=False,
        futures_enabled=False,
        otc_enabled=False,
        max_position_pct="0.25",
        stop_loss_pct="0.015",
        take_profit_pct="0.03",
        max_daily_loss_pct="0.03",
        trader_service_active=True,
        status_service_active=True,
        shadow_timer_active=True,
        open_orders_count=0,
    )


def test_valid_boundary_never_grants_execution_authority():
    result = evaluate_live_activation_stage19(valid_request())
    assert result.allowed is True
    assert result.ready_for_operator_live_env_transition is True
    assert result.approved_max_order_usdt == 10
    assert result.live_ready is False
    assert result.execution_authority is False
    assert result.order_submission_authority is False


def test_requires_stage18_guard_and_explicit_confirmation():
    assert evaluate_live_activation_stage19(
        replace(valid_request(), stage18_guard_passed=False)
    ).allowed is False
    assert evaluate_live_activation_stage19(
        replace(valid_request(), explicit_operator_confirmation=False)
    ).allowed is False


def test_requires_current_runtime_to_remain_paper_and_live_false():
    assert evaluate_live_activation_stage19(
        replace(valid_request(), current_trading_mode="live")
    ).reason == "current_runtime_must_still_be_paper"
    assert evaluate_live_activation_stage19(
        replace(valid_request(), current_live_trading_enabled="true")
    ).reason == "current_live_gate_must_still_be_false"


def test_requires_exact_10_usdt_cap():
    assert evaluate_live_activation_stage19(
        replace(valid_request(), approved_max_order_usdt="10.01")
    ).allowed is False
    assert evaluate_live_activation_stage19(
        replace(valid_request(), approved_max_order_usdt="NaN")
    ).allowed is False


def test_withdrawal_and_non_spot_authority_are_rejected():
    assert evaluate_live_activation_stage19(
        replace(valid_request(), withdrawal_permission_enabled=True)
    ).allowed is False
    for field in ("leverage_enabled", "margin_enabled", "futures_enabled", "otc_enabled"):
        assert evaluate_live_activation_stage19(
            replace(valid_request(), **{field: True})
        ).allowed is False


def test_requires_exact_risk_profile_and_ip_posture():
    assert evaluate_live_activation_stage19(
        replace(valid_request(), max_daily_loss_pct="0.031")
    ).allowed is False
    assert evaluate_live_activation_stage19(
        replace(valid_request(), exact_ip_allowlist=False)
    ).allowed is False


def test_requires_healthy_services_and_zero_open_orders():
    assert evaluate_live_activation_stage19(
        replace(valid_request(), trader_service_active=False)
    ).allowed is False
    assert evaluate_live_activation_stage19(
        replace(valid_request(), status_service_active=False)
    ).allowed is False
    assert evaluate_live_activation_stage19(
        replace(valid_request(), shadow_timer_active=False)
    ).allowed is False
    assert evaluate_live_activation_stage19(
        replace(valid_request(), open_orders_count=1)
    ).reason == "open_orders_must_be_zero_before_transition"


def test_boolean_open_order_count_is_rejected():
    assert evaluate_live_activation_stage19(
        replace(valid_request(), open_orders_count=False)
    ).reason == "open_orders_count_invalid"
