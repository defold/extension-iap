#if defined(DM_PLATFORM_HTML5) || defined(DM_PLATFORM_ANDROID) || defined(DM_PLATFORM_IOS)

#ifndef IAP_PRIVATE_H
#define IAP_PRIVATE_H

#include <dmsdk/sdk.h>

enum EIAPCommand
{
	IAP_PRODUCT_RESULT,
	IAP_PURCHASE_RESULT,
};

struct DM_ALIGNED(16) IAPCommand
{
    IAPCommand()
    {
        memset(this, 0, sizeof(IAPCommand));
    }
    int32_t  m_Command;
    int32_t  m_ResponseCode;
    void*    m_Data;
};

struct IAPCommandQueue
{
    dmArray<IAPCommand>  m_Commands;
    dmMutex::HMutex      m_Mutex;
};

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

typedef void (*IAPCommandFn)(IAPCommand* cmd, void* ctx);

void IAP_Queue_Create(IAPCommandQueue* queue);
void IAP_Queue_Destroy(IAPCommandQueue* queue);
// The command is copied by value into the queue
void IAP_Queue_Push(IAPCommandQueue* queue, IAPCommand* cmd);
void IAP_Queue_Flush(IAPCommandQueue* queue, IAPCommandFn fn, void* ctx);

#endif

#endif // DM_PLATFORM_HTML5 || DM_PLATFORM_ANDROID || DM_PLATFORM_IOS
