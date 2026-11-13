---
title: Defold In-app purchase extension API documentation
brief: This manual covers how to setup and use In-App Purchases in Defold.
---

# Defold In-app purchase extension API documentation

This extension provides a unified, simple to use interface to several different stores for in-app purchase:

* Apple’s iOS Appstore - StoreKit 2
* Google Play Billing 5.0
* Amazon 'in-app billing' 2.0.61
* Facebook Canvas 'game payments'

These services gives you the opportunity to sell products as:

* Standard in-app products (one time billing) of consumables or non-consumables and
* Subscriptions (recurring, automated billing)

::: important
The current Defold interface allows full interaction with Apple's Storekit functionality. For Google Play and Facebook Canvas, the interface is identical, meaning that you can run the same code on either platform. However, some process flow might differ from platform to platform. Also note that there is currently no support for OS X purchases through the Mac Appstore.
:::

Detailed documentation from Apple, Google, Amazon and Facebook can be found here:

* [In-App Purchase with StoreKit](https://developer.apple.com/documentation/storekit/in-app-purchase).
* [Google Play In-app Billing documentation](http://developer.android.com/google/play/billing/index.html).
* [Amazon In-app Purchase documentation](https://developer.amazon.com/public/apis/earn/in-app-purchasing).
* [Facebook game payments documentation](https://developers.facebook.com/docs/payments).

## Installation
To use this library in your Defold project, add the following URL to your `game.project` dependencies:

[https://github.com/defold/extension-iap/archive/master.zip](https://github.com/defold/extension-iap/archive/master.zip)

We recommend using a link to a zip file of a [specific release](https://github.com/defold/extension-iap/releases).

For Facebook Canvas you also need to add the [Facebook extension as a dependency](https://github.com/defold/extension-facebook).

### iOS requirements

On iOS the extension uses StoreKit 2, which is only available from Swift, so the extension contains [Swift sources](/manuals/extensions-swift). This has the following requirements:

* iOS 15.0 or later, which is the minimum version of StoreKit 2.
* A Defold version and a build server with support for Swift sources in native extensions. Older versions fail to build the project for iOS, and depending on the build server also for macOS, with an error such as `Swift sources are not supported by this Defold SDK version` or `'IAPExt-Swift.h' file not found`. Use an earlier [release](https://github.com/defold/extension-iap/releases) of the extension with those versions.


## Testing Google Play Billing with static responses

On Android it is recommended that you start implementing IAP in your app by using static responses from Google Play. This enables you to verify that everything in your app works correctly before you publish the app. Four reserved product IDs exist for testing static In-app Billing responses:

`android.test.purchased`
: Google Play responds as though you successfully purchased an item. The response includes a JSON string, which contains fake purchase information (for example, a fake order ID).

`android.test.canceled`
: Google Play responds as though the purchase was canceled. This can occur when an error is encountered in the order process, such as an invalid credit card, or when you cancel a user's order before it is charged.

`android.test.refunded`
: Google Play responds as though the purchase was refunded.

`android.test.item_unavailable`
: Google Play responds as though the item being purchased was not listed in your application's product list.


## Setting up your app for purchases/billing

The procedure on iOS and Android is similar:

1. Make sure you are a registered Apple or Google Play developer.
2. Set up your project so it works on your target device. See the [iOS development](/manuals/ios) and [Android development](/manuals/android) guides.
3. Set up the app for testing:

    - For Android, this is done on the [Google Play Developer Console](https://play.google.com/apps/publish/).
    - For iOS, this is done on [iTunes Connect](https://itunesconnect.apple.com/). Make sure that your App ID (created in the "Member Center" on https://developer.apple.com) has "In-App Purchase" enabled.

    ![iTunes Connect and Google Play Dev Console](itunes_connect_google_play.png)

4. For Google Play, you need to _upload and publish_ an alpha *.apk* file. For iTunes Connect, you should _not upload_ the development binary to iTunes Connect until the application is ready for App Review approval. If you upload a binary to iTunes Connect and it is not fully functional, Apple will likely reject it.

5. Create products for your app.

    ![iTunes Products](itunes_products.png)

    ![Google Play Products](google_play_products.png)

6. Add test users.
    - The iTunes Connect page *Users and Roles* allow you to add users that can do test purchases in the _sandbox environment_. You should sign your app with a Developer certificate and use the sandbox account in Appstore on the test device.
    - From the Google Play Developer Console, choose *Settings > Account Details* where you can add user emails to the License Testing section. Separate the emails by commas. This allows your testers to use test purchases that don’t actually cost real money.
    - On Google Play, you also need to set up a Google Group for your testers. Google uses Groups to manage testers that can download your app from the Alpha and Beta stores. Click on the *Alpha Testing* tab and then *Manage list of testers* to add your Google Group as Alpha testers. The app must have passed through alpha publishing before you can see the opt-in link.

![Alpha testers](alpha_testers.png)

The procedure on Facebook:

1. Make sure you are a registered Facebook developer. Go to [Facebook for developers](https://developers.facebook.com/), "My Apps" and "Register as a developer", follow the steps.
2. Facebook has extensive payment functionality and requires support of both synchronous and asynchronous payments. More info here [Payment overview](https://developers.facebook.com/docs/payments/overview)
3. Set up app hosting and callback server:
    * You will need to set up a secure canvas URL hosting your project. How this works is explained here [Games on Facebook](https://developers.facebook.com/docs/games/gamesonfacebook/hosting).
    * The next step is to set up your callback server. Follow the steps here [Setting up your callback server](https://developers.facebook.com/docs/payments/realtimeupdates#yourcallbackserver).
4. Set up you canvas app. Follow the steps on [Facebook Developer Dashboard](https://developers.facebook.com/quickstarts/?platform=canvas).
5. Add test users. This is done in the "Canvas Payments" section of the app dashboard.
6. Create products for your app [Defining products](https://developers.facebook.com/docs/payments/implementation-guide/defining-products/).


## Asynchronous transactions

The IAP API is asynchronous, meaning that after each request that your program sends to the server, the program will not halt and wait for a response. Instead, the program continues as ordinary and when the response arrives, a _callback_ function is invoked where you can react to the response data.

To fetch all product information available:

```lua
local COINS_ID = "com.defold.examples.coins"
local LOGO_ID = "com.defold.examples.logo"

local function product_list(self, products, error)
    if error == nil then
        for i,p in pairs(products) do
            print(p.ident)
            print(p.title)
            print(p.description)
            print(p.currency_code)
            print(p.price_string)
        end
    else
        print(error.error)
    end
end

function init(self)
    -- Initiate a fetch of products (max 20 at a time for Google Play)
    iap.list({ COINS_ID, LOGO_ID }, product_list)
end
```

To perform actual transactions, first register a function that will listen to transaction results, then call the store function at the appropriate time:

```lua
local function iap_listener(self, transaction, error)
    if error == nil then
        if transaction.state == iap.TRANS_STATE_PURCHASING then
            print("Purchasing...")
        elseif transaction.state == iap.TRANS_STATE_PURCHASED then
            print("Purchased!")
        elseif transaction.state == iap.TRANS_STATE_UNVERIFIED then
            print("Unverified!")
        elseif transaction.state == iap.TRANS_STATE_FAILED then
            print("Failed!")
        elseif transaction.state == iap.TRANS_STATE_RESTORED then
            print("Restored")
        end
    else
        print(error.error)
    end
end

function on_message(self, message_id, message, sender)
    ...
    -- Register the function that will listen to IAP transactions.
    iap.set_listener(iap_listener)
    -- Initiate a purchase of a coin...
    iap.buy(COINS_ID)
    ...
end
```

The device operating system will automatically show a pop-up window allowing the user to go through with the purchase. The interface clearly indicates when you are running in the test/sandbox environment.

![Confirm purchase](ios_confirm_purchase.png)

![Android purchase](android_purchase.png)

![Confirm purchase](ios_purchase_done.png)


## Synchronous payments

Most payment providers only supports synchronous payments. This means that the client (your application) will receive a notification when the payment is complete, TRANS_STATE_PURCHASED. This is the final state of the payment, meaning no more callbacks will be done on this transaction.


## Asynchronous payments

Some payment providers require supporting asynchronous payments. This means that the client (your application) will only receive a notification when the payment is initiated. In order to verify completion of payment, further communication needs to be done between the developer server (or client) and the payment provider in order to verify.

In the case of an initiated asynchronous payment the IAP listener will receive the state TRANS_STATE_UNVERIFIED to indicate this (as opposed to TRANS_STATE_PURCHASED). This is the final state of the payment, meaning no more callbacks will be done on this transaction. The exception is iOS, where a pending purchase can later be completed, see [StoreKit 2 on iOS](#storekit-2-on-ios).


## Purchase fulfilment

In order to complete a purchase from a payment provider, the application needs to signal a purchase fulfilment to the provider telling the provider the purchase has gone through (for example by developer server-side verification).

IAP supports auto-completion, where fulfilment is automatically signalled to the provider when a purchase is complete (this is the default behavior). You can also disable auto-completion in the game project settings. You are then required to call `iap.finish()` when the transaction is complete, which will signal purchase fulfilment to the provider.


### Consumable vs non-consumable products

#### Google Play

It is recommended to use manual fulfilment of purchases and never finish purchases for products that should be non-consumable. As long as a purchase isn't finished it will be returned as an active purchase when `iap.set_listener()` is called. If you do not call `iap.finish()` on a purchase you still need to indicate to Google Play that the purchase has been handled. You can do this by calling `iap.acknowledge()`. If you do not call `iap.acknowledge()` the purchase will be automatically refunded by Google after a few days.

#### App Store

The Apple App Store supports non-consumable products which means that you need to finish all purchases when you provide products to your users. You can do it automatically by keeping the default behavior in the game project settings or manually (if you want to do that after server validation, for example) using `iap.finish()`.


## Transaction receipt

The receipt is a signed chunk of data that can be sent to the App Store to verify that the payment was successfully processed. This is most useful when designing a store that uses a separate server to verify that payment was processed.

On iOS, use the `jws_representation` field of the transaction for this. It is the transaction signed by the App Store as a JSON Web Signature (JWS), which your server can verify by itself or with the [App Store Server API](https://developer.apple.com/documentation/appstoreserverapi). The `receipt` field of a purchased transaction still holds the contents of the app receipt if the file exists on the device, but Apple has deprecated the app receipt and its `verifyReceipt` endpoint.


## StoreKit 2 on iOS

StoreKit reports purchases that are made outside of the application, such as approved Ask to Buy requests, subscription renewals and purchases on other devices, while the application is running, and unfinished transactions when the application starts. The extension sends them to the listener. A transaction that arrives before `iap.set_listener()` is called, or while there is no listener, is not lost: it stays unfinished and `iap.process_pending_transactions()` sends all unfinished transactions to the listener again. The result of a purchase that completes after the extension has been restarted, for example with `sys.reboot()`, is sent to the listener of the new session.

StoreKit can send a transaction again, for example after the application is restarted or when the transaction is updated, even if it has been finished. Use `trans_ident` to make sure that the content of a transaction is only delivered once.

Unverified transactions
: If StoreKit can't verify the signature of a transaction, the listener receives it with the state `TRANS_STATE_UNVERIFIED` and an `error`. Don't deliver the content based on the transaction itself. The transaction is never finished automatically and it comes back when the application starts. If your server verifies the `jws_representation` and accepts it, call `iap.finish()` on the transaction. On iOS this also works when `auto_finish_transactions` is enabled.

Ask to Buy and other pending purchases
: The listener receives the state `TRANS_STATE_UNVERIFIED` without an `error` or a `trans_ident`. If the purchase is approved, the listener later receives the transaction with the state `TRANS_STATE_PURCHASED`. StoreKit 2 doesn't notify the application if the purchase is declined.

Restoring purchases
: `iap.restore()` synchronizes the transactions with the App Store, which may ask the user to sign in, so only call it when the user asks for it. The listener then receives the latest transaction of each product the user is entitled to with the state `TRANS_STATE_RESTORED`: non-consumable products, active auto-renewable subscriptions and non-renewing subscriptions. StoreKit returns non-renewing subscriptions even after they have expired, so check the `date` of the transaction against the duration of the subscription. Refunded products and subscriptions replaced by an upgrade are left out. A restored transaction can be a purchase that hasn't been finished yet. It is finished like a purchased transaction: automatically if `auto_finish_transactions` is enabled, otherwise call `iap.finish()` on it. Finishing a transaction that was already finished has no effect. A transaction that StoreKit can't verify is received with the state `TRANS_STATE_UNVERIFIED` and an `error`, as described above. If the synchronization fails, a warning is logged and the listener receives the transactions known to the device.

    Compared to the original StoreKit API, a restored transaction is the purchase itself and not a new restore transaction: `trans_ident` and `date` are those of the purchase, or of the latest renewal of a subscription, and earlier renewals are not sent again. A restored transaction and its `original_trans` don't have a `receipt`, use `jws_representation` to verify them.

Refunds and subscription upgrades
: A transaction that has been refunded, revoked or replaced by a subscription upgrade is finished without calling the listener. Use [App Store Server Notifications](https://developer.apple.com/documentation/appstoreservernotifications) to get notified of those on your server.


## Differences between supported platforms

Amazon supports two different product types: subscriptions and consumable products.

Google Play and Apple supports three different product types: subscriptions, consumable and non-consumable products.

If you want to simulate non-consumable products on Amazon you need to make sure to not call `iap.finish()` on the product in question (and make sure to not have enabled Auto Finish Transactions in *game.project*).

Calls to `iap.buy()` and `iap.set_listener()` will return all non-finished purchases on Google Play. (This will not happen on iOS, where `iap.process_pending_transactions()` returns them)

The concept of restoring purchases does not exist on Google Play/Amazon. Calls to `iap.restore()` on iOS will return all non-consumable products and active subscriptions (and have product state set to TRANS_STATE_RESTORED). Calls to `iap.restore()` on Google Play will return all non-finished purchases (and have product state set to TRANS_STATE_PURCHASED).


## Troubleshooting

Android `iap.list()` returns "failed to fetch product"
: You need to upload and publish an *.apk* on the alpha or beta channels on the Google Play Developer Console. Also make sure that the _time and date_ on your device is correct.

Android (Google Play) `iap.list()` never returns more than 20 products
: Google has a [limit of 20 products per request](https://github.com/android/play-billing-samples/blob/7a94c6905a9c125518354c216b5c3094fde47ce1/TrivialDrive/app/src/main/aidl/com/android/vending/billing/IInAppBillingService.aidl#L62). The solution is to make multiple calls to `iap.list()` and combine the results if the number of products exceeds 20.

iOS `iap.list()` returns nothing
: Make sure that you’ve requested an iOS Paid Applications account, and all proper documentation has been filed. Without proper authorization, your iOS app purchasing (even test purchases) will not work.

  Check that the AppId you have on the "Member Center" has in-app purchases activated and that you are signing your app (or the dev-app) with a provisioning profile that is up to date with the AppId (check the "Enabled Services:" field in the provisioning profile details in the "Certificates, Identifiers & Profiles" area of "Member Center")

  Wait. It can take a few hours for the In-App product IDs to propagate to the Sandbox environment.

iOS `iap.list()` fails logging error "Unexpected callback set"
: `iap.list()` does not support nested calls. Calling `iap.list()` from an `iap.list()` callback function will be ignored, with the engine logging this error.

On iOS, the "price_string" field contains '~' characters
: The '~' characters are placeholders where no matching character could be found in the font file. The "price_string" field returned in the product list when using `iap.list()` is formatted with a _non breaking space_ (`\u00a0`) between the value and the currency denominator. If you render this string in the GUI, you need to add the character to the font's *extra_characters* field. On Mac OS X you can type non breaking spaces by pressing <kbd>Option + SPACE</kbd>. See http://en.wikipedia.org/wiki/Non-breaking_space for more information.


## Source code

The source code is available on [GitHub](https://github.com/defold/extension-iap)
