---
layout: default
---

# Defold In-app purchase extension API documentation

This extension provides functions for making in-app purchases.


## Usage
To use this library in your Defold project, add the following URL to your `game.project` dependencies:

    https://github.com/defold/extension-iap/archive/master.zip

We recommend using a link to a zip file of a [specific release](https://github.com/defold/extension-iap/releases).

For Facebook Canvas you also need to add the [Facebook extension as a dependency](https://github.com/defold/extension-facebook).


## Source code

The source code is available on [GitHub](https://github.com/defold/extension-iap)


## Supported platforms

The following platforms are supported by the extension:

* iOS - StoreKit
* Google Play - Billing 3.0.0
* Amazon - 2.0.61
* Facebook Canvas


### Differences between supported platforms

Amazon supports two different product types: subscriptions and consumable products.

Google Play and Apple supports three different product types: subscriptions, consumable and non-consumable products.

If you want to simulate non-consumable products on Amazon you need to make sure to not call `iap.finish()` on the product in question (and make sure to not have enabled Auto Finish Transactions in *game.project*).

Calls to `iap.buy()` and `iap.set_listener()` will return all non-finished purchases on Google Play. (This will not happen on iOS)

The concept of restoring purchases does not exist on Google Play/Amazon. Calls to `iap.restore()` on iOS will return all purchased products (and have product state set to TRANS_STATE_RESTORED). Calls to `iap.restore()` on Google Play will return all non-finished purchases (and have product state set to TRANS_STATE_PURCHASED).

---

# API reference

{% include api_ref.md %}
