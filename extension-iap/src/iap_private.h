#if defined(DM_PLATFORM_HTML5) || defined(DM_PLATFORM_ANDROID) || defined(DM_PLATFORM_IOS)

#ifndef IAP_PRIVATE_H
#define IAP_PRIVATE_H

#include <dmsdk/sdk.h>

// TODO: Rename Callback
struct IAPListener
{
    IAPListener();

    lua_State* m_L;
    int        m_Callback;
    int        m_Self;
};

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

    // Used for storing eventual callback info (if needed)
    IAPListener m_Callback;

    // THe actual command payload
    int32_t  	m_Command;
    int32_t  	m_ResponseCode;
    void*    	m_Data;
};

struct IAPCommandQueue
{
    dmArray<IAPCommand>  m_Commands;
    dmMutex::HMutex      m_Mutex;
};

void IAP_ClearCallback(IAPListener* callback);
void IAP_RegisterCallback(lua_State* L, int index, IAPListener* callback);
void IAP_UnregisterCallback(IAPListener* callback);
bool IAP_SetupCallback(IAPListener* callback);
bool IAP_CallbackIsValid(IAPListener* callback);

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
