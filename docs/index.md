---
layout: default
---

# Defold In-app purchase extension API documentation

This extension provides functions for making in-app purchases. Supported on iOS, Android (Google Play and Amazon) and Facebook Canvas.

# Usage
To use this library in your Defold project, add the following URL to your <code class="inline-code-block">game.project</code> dependencies:

    https://github.com/defold/extension-iap/archive/master.zip

We recommend using a link to a zip file of a [specific release](https://github.com/defold/extension-iap/releases).

## Source code

The source code is available on [GitHub](https://github.com/defold/extension-iap)

## Differences between supported platforms

Google Play and Amazon supports two different product types: subscriptions and consumable products.

Apple supports three different product types: subscriptions, consumable and non-consumable products.

If you want to simulate non-consumable products on Google Play/Amazon you need to make sure to not call `iap.finish()` on the product in question (and make sure to not have enabled Auto Finish Transactions in *game.project*).

Calls to `iap.buy()` and `iap.set_listener()` will return all non-finished purchases on Google Play. (This will not happen on iOS)

The concept of restoring purchases does not exist on Google Play/Amazon. Calls to `iap.restore()` on iOS will return all purchased products (and have product state set to TRANS_STATE_RESTORED). Calls to `iap.restore()` on Google Play will return all non-finished purchases (and have product state set to TRANS_STATE_PURCHASED).

###

# API reference

{% include api_ref.md %}
