#if defined(DM_PLATFORM_HTML5) || defined(DM_PLATFORM_ANDROID) || defined(DM_PLATFORM_IOS)

#ifndef IAP_PRIVATE_H
#define IAP_PRIVATE_H

#include <dmsdk/sdk.h>

struct IAPListener
{
    IAPListener()
    {
        m_L = 0;
        m_Callback = LUA_NOREF;
        m_Self = LUA_NOREF;
    }
    lua_State* m_L;
    int        m_Callback;
    int        m_Self;
};


char* IAP_List_CreateBuffer(lua_State* L);
void IAP_PushError(lua_State* L, const char* error, int reason);
void IAP_PushConstants(lua_State* L);

#endif

#endif // DM_PLATFORM_HTML5 || DM_PLATFORM_ANDROID || DM_PLATFORM_IOS
