"""Authenticated PostgreSQL-backed management and shared Redis dependency checks."""
import copy
import json
import os
import uuid

import pytest
from test_compose import call, chat, compose
from test_phase2 import stats

pytestmark=pytest.mark.skipif(os.getenv("SENTINEL_COMPOSE_TESTS")!="1",reason="Opt-in Compose integration")


def management(path="/config",payload=None,key="SENTINEL_OTHER_OPERATOR_KEY",method=None):
    return call(payload,"/api/v1/management"+path,key_name=key,method=method)


def update(config):
    result=copy.deepcopy(config);result["expected_revision"]=result.pop("revision");result["policy"]["version"]=result["expected_revision"]+1;result["registry"]["version"]=result["expected_revision"]+1;return result


def test_management_permissions_and_query_validation():
    assert management(key="SENTINEL_DEMO_KEY")[0]==403
    status,config=management(key="SENTINEL_VIEWER_KEY");assert status==200
    assert management(payload=update(config),key="SENTINEL_VIEWER_KEY",method="PUT")[0]==403
    for path in ("/events?limit=101","/events?tenant_id=demo","/events?action=invalid","/events?before=-1","/events?limit=1&limit=2"):
        assert management(path)[0]==400
    assert management("/operations/"+str(uuid.uuid4()))[0]==404


def test_policy_update_enforces_next_request_and_stale_revision_conflicts():
    status,original=management();assert status==200
    payload=chat("Contact alice@example.test")
    assert call(payload,key_name="SENTINEL_OTHER_DEMO_KEY")[0]==200
    candidate=update(original);candidate["policy"]["actions"]["pii"]["prompt"]="deny"
    try:
        status,current=management(payload=candidate,method="PUT");assert status==200
        assert call(payload,key_name="SENTINEL_OTHER_DEMO_KEY")[0]==403
        assert management(payload=candidate,method="PUT")[0]==409
        status,history=management("/config/history?limit=1");assert status==200 and len(history["versions"])==1 and "next_cursor" in history
    finally:
        status,current=management();assert status==200
        restore=update(current);restore["policy"]=copy.deepcopy(original["policy"]);restore["registry"]=copy.deepcopy(original["registry"]);restore["policy"]["version"]=restore["expected_revision"]+1;restore["registry"]["version"]=restore["expected_revision"]+1
        assert management(payload=restore,method="PUT")[0]==200


def test_operation_events_are_tenant_scoped_and_survive_gateway_restart():
    status,response=call(chat("synthetic durable request"),key_name="SENTINEL_OTHER_DEMO_KEY");assert status==200
    operation=response["sentinel"]["operation_id"]
    path="/operations/"+operation
    status,trace=management(path);assert status==200
    assert trace["operation"]["status"]=="completed" and trace["operation"]["external_state"]=="returned"
    assert len(trace["events"])==3
    assert management(path,key="SENTINEL_OPERATOR_KEY")[0]==404
    assert management("/events?operation_id="+operation,key="SENTINEL_OPERATOR_KEY")[1]["events"]==[]
    compose("restart","gateway")
    compose("up","-d","--wait")
    assert management(path)[1]==trace


def test_database_outage_prevents_mcp_execution_and_management():
    before=stats()
    compose("stop","postgres")
    try:
        status,response=call({"tool_id":"kb.search","arguments":{"query":"safe"}},"/api/v1/tools/execute",key_name="SENTINEL_OTHER_DEMO_KEY")
        assert status==503 and "result" not in response
        assert management()[0]==503
        assert stats()==before
    finally:
        compose("up","-d","--wait")
    assert management()[0]==200


def test_redis_outage_prevents_execution_and_recovers():
    before=stats();compose("stop","redis")
    try:
        status,response=call({"tool_id":"kb.search","arguments":{"query":"safe"}},"/api/v1/tools/execute",key_name="SENTINEL_OTHER_DEMO_KEY")
        assert status==503 and "result" not in response
        assert stats()==before
    finally:
        compose("up","-d","--wait")
    assert call({"tool_id":"kb.search","arguments":{"query":"safe"}},"/api/v1/tools/execute",key_name="SENTINEL_OTHER_DEMO_KEY")[0]==200
