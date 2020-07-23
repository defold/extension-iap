#if defined(DM_PLATFORM_HTML5)

#include <dmsdk/sdk.h>

#include <stdlib.h>
#include <unistd.h>

#include "iap.h"
#include "iap_private.h"

#define LIB_NAME "iap"

struct IAP
{
    IAP()
    {
        memset(this, 0, sizeof(*this));
        m_autoFinishTransactions = true;
    }

    dmScript::LuaCallbackInfo*  m_Listener;
    int                         m_InitCount;
    bool                        m_autoFinishTransactions;
} g_IAP;

typedef void (*OnIAPFBList)(void* luacallback, const char* json);
typedef void (*OnIAPFBListenerCallback)(void* luacallback, const char* json, int error_code);

extern "C" {
    // Implementation in library_facebook_iap.js
    void dmIAPFBList(const char* item_ids, OnIAPFBList callback, dmScript::LuaCallbackInfo* luacallback);
    void dmIAPFBBuy(const char* item_id, const char* request_id, OnIAPFBListenerCallback callback, dmScript::LuaCallbackInfo* luacallback);
}

static void IAPList_Callback(void* luacallback, const char* result_json)
{
    dmScript::LuaCallbackInfo* callback = (dmScript::LuaCallbackInfo*)luacallback;
    lua_State* L = dmScript::GetCallbackLuaContext(callback);
    DM_LUA_STACK_CHECK(L, 0);

    if (!dmScript::SetupCallback(callback))
    {
        dmScript::DestroyCallback(callback);
        return;
    }

    if(result_json != 0)
    {
        dmJson::Document doc;
        dmJson::Result r = dmJson::Parse(result_json, &doc);
        if (r == dmJson::RESULT_OK && doc.m_NodeCount > 0) {
            char err_str[128];
            if (dmScript::JsonToLua(L, &doc, 0, err_str, sizeof(err_str)) < 0) {
                dmLogError("Failed converting list result JSON to Lua; %s", err_str);
                lua_pushnil(L);
                IAP_PushError(L, "Failed converting list result JSON to Lua", REASON_UNSPECIFIED);
            } else {
                lua_pushnil(L);
            }
        } else {
            dmLogError("Failed to parse list result JSON (%d)", r);
            lua_pushnil(L);
            IAP_PushError(L, "Failed to parse list result JSON", REASON_UNSPECIFIED);
        }
        dmJson::Free(&doc);
    }
    else
    {
        dmLogError("Got empty list result.");
        lua_pushnil(L);
        IAP_PushError(L, "Got empty list result.", REASON_UNSPECIFIED);
    }

    dmScript::PCall(L, 3, 0);

    dmScript::DestroyCallback(callback);
    dmScript::TeardownCallback(callback);
}

static int IAP_ProcessPendingTransactions(lua_State* L)
{
    return 0;
}

static int IAP_List(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    char* buf = IAP_List_CreateBuffer(L);
    if( buf == 0 )
    {
        return 0;
    }

    dmScript::LuaCallbackInfo* callback = dmScript::CreateCallback(L, 2);

    dmIAPFBList(buf, (OnIAPFBList)IAPList_Callback, callback);

    free(buf);
    return 0;
}

static void IAPListener_Callback(void* luacallback, const char* result_json, int error_code)
{
    dmScript::LuaCallbackInfo* callback = (dmScript::LuaCallbackInfo*)luacallback;
    lua_State* L = dmScript::GetCallbackLuaContext(callback);
    DM_LUA_STACK_CHECK(L, 0);

    if (!dmScript::SetupCallback(callback))
    {
        return;
    }

    if (result_json) {
        dmJson::Document doc;
        dmJson::Result r = dmJson::Parse(result_json, &doc);
        if (r == dmJson::RESULT_OK && doc.m_NodeCount > 0) {
            char err_str[128];
            if (dmScript::JsonToLua(L, &doc, 0, err_str, sizeof(err_str)) < 0) {
                dmLogError("Failed converting purchase result JSON to Lua; %s", err_str);
                lua_pushnil(L);
                IAP_PushError(L, "failed converting purchase result JSON to Lua", REASON_UNSPECIFIED);
            } else {
                lua_pushnil(L);
            }
        } else {
            dmLogError("Failed to parse purchase response (%d)", r);
            lua_pushnil(L);
            IAP_PushError(L, "failed to parse purchase response", REASON_UNSPECIFIED);
        }
        dmJson::Free(&doc);
    } else {
        lua_pushnil(L);
        switch(error_code)
        {
            case BILLING_RESPONSE_RESULT_USER_CANCELED:
                IAP_PushError(L, "user canceled purchase", REASON_USER_CANCELED);
                break;

            case BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED:
                IAP_PushError(L, "product already owned", REASON_UNSPECIFIED);
                break;

            default:
                dmLogError("IAP error %d", error_code);
                IAP_PushError(L, "failed to buy product", REASON_UNSPECIFIED);
                break;
        }
    }

    dmScript::PCall(L, 3, 0);

    dmScript::TeardownCallback(callback);
}


static int IAP_Buy(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    if (!g_IAP.m_Listener) {
        dmLogError("No callback set");
        return 0;
    }

    int top = lua_gettop(L);
    const char* id = luaL_checkstring(L, 1);
    const char* request_id = 0x0;

    if (top >= 2 && lua_istable(L, 2)) {
        luaL_checktype(L, 2, LUA_TTABLE);
        lua_pushvalue(L, 2);
        lua_getfield(L, -1, "request_id");
        request_id = lua_isnil(L, -1) ? 0x0 : luaL_checkstring(L, -1);
        lua_pop(L, 2);
    }

    dmIAPFBBuy(id, request_id, (OnIAPFBListenerCallback)IAPListener_Callback, g_IAP.m_Listener);
    return 0;
}

static int IAP_SetListener(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);
    if (g_IAP.m_Listener)
        dmScript::DestroyCallback(g_IAP.m_Listener);
    g_IAP.m_Listener = dmScript::CreateCallback(L, 1);
    return 0;
}

static int IAP_Finish(lua_State* L)
{
    return 0;
}

static int IAP_Acknowledge(lua_State* L)
{
    return 0;
}

static int IAP_Restore(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 1);
    lua_pushboolean(L, 0);
    return 1;
}

static int IAP_GetProviderId(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 1);
    lua_pushinteger(L, PROVIDER_ID_FACEBOOK);
    return 1;
}

static const luaL_reg IAP_methods[] =
{
    {"list", IAP_List},
    {"buy", IAP_Buy},
    {"finish", IAP_Finish},
    {"acknowledge", IAP_Acknowledge},
    {"restore", IAP_Restore},
    {"set_listener", IAP_SetListener},
    {"get_provider_id", IAP_GetProviderId},
    {"process_pending_transactions", IAP_ProcessPendingTransactions},
    {0, 0}
};

static dmExtension::Result InitializeIAP(dmExtension::Params* params)
{
    if (g_IAP.m_InitCount == 0) {
        g_IAP.m_autoFinishTransactions = dmConfigFile::GetInt(params->m_ConfigFile, "iap.auto_finish_transactions", 1) == 1;
    }
    g_IAP.m_InitCount++;
    lua_State* L = params->m_L;
    int top = lua_gettop(L);
    luaL_register(L, LIB_NAME, IAP_methods);

    IAP_PushConstants(L);

    lua_pop(L, 1);
    assert(top == lua_gettop(L));
    return dmExtension::RESULT_OK;
}

static dmExtension::Result FinalizeIAP(dmExtension::Params* params)
{
    --g_IAP.m_InitCount;
    if (g_IAP.m_Listener && g_IAP.m_InitCount == 0)
    {
        dmScript::DestroyCallback(g_IAP.m_Listener);
        g_IAP.m_Listener = 0;
    }
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(IAPExt, "IAP", 0, 0, InitializeIAP, 0, 0, FinalizeIAP)

#endif // DM_PLATFORM_HTML5
