#if os(iOS)

import Foundation
import StoreKit

// The raw values match TransactionState in iap.h
@objc(IAPExtTransactionState)
public enum StoreTransactionState: Int32 {
    case purchasing = 0
    case purchased = 1
    case failed = 2
    case restored = 3
    case unverified = 4
}

// The raw values match ErrorReason in iap.h
@objc(IAPExtErrorReason)
public enum StoreErrorReason: Int32 {
    case unspecified = 0
    case userCanceled = 1
}

@objc(IAPExtProduct)
public final class StoreProduct: NSObject {
    @objc public let ident: String
    @objc public let title: String
    @objc public let desc: String
    @objc public let price: Float
    @objc public let priceString: String
    @objc public let currencyCode: String

    init(_ product: Product) {
        ident = product.id
        title = product.displayName
        desc = product.description
        price = NSDecimalNumber(decimal: product.price).floatValue
        priceString = product.displayPrice
        currencyCode = product.priceFormatStyle.currencyCode
    }
}

@objc(IAPExtTransaction)
public final class StoreTransaction: NSObject {
    @objc public let ident: String
    @objc public let state: StoreTransactionState
    @objc public let date: Date?
    @objc public let transIdent: String?
    @objc public let receipt: Data?
    @objc public let jwsRepresentation: String?
    @objc public let originalTransaction: StoreTransaction?
    @objc public let error: String?
    @objc public let errorReason: StoreErrorReason
    // Set for the result of iap.buy() and iap.restore(), which is delivered even if the transaction was finished before
    @objc public let isRequested: Bool

    init(ident: String, state: StoreTransactionState, date: Date? = nil, transIdent: String? = nil, error: String? = nil, errorReason: StoreErrorReason = .unspecified) {
        self.ident = ident
        self.state = state
        self.date = date
        self.transIdent = transIdent
        self.receipt = nil
        self.jwsRepresentation = nil
        self.originalTransaction = nil
        self.error = error
        self.errorReason = errorReason
        self.isRequested = true
    }

    init(_ result: VerificationResult<Transaction>, state: StoreTransactionState, isRequested: Bool) {
        let transaction = result.unsafePayloadValue
        ident = transaction.productID
        date = transaction.purchaseDate
        transIdent = String(transaction.id)
        jwsRepresentation = result.jwsRepresentation
        errorReason = .unspecified
        self.isRequested = isRequested

        if case .unverified(_, let verificationError) = result {
            self.state = .unverified
            error = verificationError.localizedDescription
        } else {
            self.state = state
            error = nil
        }

        receipt = self.state == .purchased ? Bundle.main.appStoreReceiptURL.flatMap { try? Data(contentsOf: $0) } : nil
        originalTransaction = self.state == .restored
            ? StoreTransaction(ident: transaction.productID, state: .purchased, date: transaction.originalPurchaseDate, transIdent: String(transaction.originalID))
            : nil
    }
}

@objc(IAPExtStore)
public final class Store: NSObject {
    private let transactionHandler: (StoreTransaction) -> Void
    private let lock = NSLock()
    private var isStopped = false
    private var unfinished: [String: Transaction] = [:]
    private var finished = Set<String>()
    private var updatesTask: Task<Void, Never>?

    @objc public init(transactionHandler: @escaping (StoreTransaction) -> Void) {
        self.transactionHandler = transactionHandler
        super.init()
        updatesTask = Task { [weak self] in
            for await result in Transaction.updates {
                self?.process(result, source: .update)
            }
        }
    }

    // No callback is running or starts after this returns
    @objc public func stop() {
        lock.lock()
        isStopped = true
        lock.unlock()
        updatesTask?.cancel()
    }

    @objc public func listProducts(_ idents: [String], completion: @escaping ([StoreProduct], String?) -> Void) {
        Task {
            do {
                let products = try await Product.products(for: idents).map { StoreProduct($0) }
                deliver { completion(products, nil) }
            } catch {
                deliver { completion([], error.localizedDescription) }
            }
        }
    }

    @objc public func buy(_ ident: String) {
        report(StoreTransaction(ident: ident, state: .purchasing))
        Task {
            do {
                guard let product = try await Product.products(for: [ident]).first else {
                    report(StoreTransaction(ident: ident, state: .failed, error: "Unknown product"))
                    return
                }
                switch try await product.purchase() {
                case .success(let result):
                    process(result, source: .purchase)
                case .userCancelled:
                    report(StoreTransaction(ident: ident, state: .failed, error: "Purchase canceled by the user", errorReason: .userCanceled))
                case .pending:
                    report(StoreTransaction(ident: ident, state: .unverified))
                @unknown default:
                    report(StoreTransaction(ident: ident, state: .failed, error: "Unknown purchase result"))
                }
            } catch {
                var reason = StoreErrorReason.unspecified
                if case StoreKitError.userCancelled = error {
                    reason = .userCanceled
                }
                report(StoreTransaction(ident: ident, state: .failed, error: error.localizedDescription, errorReason: reason))
            }
        }
    }

    @objc public func processPendingTransactions() {
        Task {
            for await result in Transaction.unfinished {
                process(result, source: .unfinished)
            }
        }
    }

    // The completion is called with the error if the sync with the App Store failed
    @objc public func restore(completion: @escaping (String) -> Void) {
        Task {
            do {
                try await AppStore.sync()
            } catch {
                deliver { completion(error.localizedDescription) }
            }
            for await result in Transaction.currentEntitlements {
                process(result, source: .restore)
            }
        }
    }

    @discardableResult
    @objc public func finishTransaction(_ transIdent: String) -> Bool {
        lock.lock()
        let transaction = unfinished.removeValue(forKey: transIdent)
        if transaction != nil {
            finished.insert(transIdent)
        }
        lock.unlock()

        guard let transaction else {
            return false
        }
        Task {
            await transaction.finish()
        }
        return true
    }

    @objc public func isTransactionFinished(_ transIdent: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return finished.contains(transIdent)
    }

    private enum Source {
        case purchase, update, unfinished, restore
    }

    private func process(_ result: VerificationResult<Transaction>, source: Source) {
        let transaction = result.unsafePayloadValue

        if case .verified = result, transaction.revocationDate != nil || transaction.isUpgraded {
            if source != .restore {
                Task {
                    await transaction.finish()
                }
            }
            return
        }

        // A restored transaction that StoreKit verified is not finished again, an unverified one can be
        var isKnown = false
        if source != .restore || result.isUnverified {
            lock.lock()
            isKnown = unfinished.updateValue(transaction, forKey: String(transaction.id)) != nil
            lock.unlock()
        }

        if source == .update && isKnown {
            return
        }
        let isRequested = source == .purchase || source == .restore
        report(StoreTransaction(result, state: source == .restore ? .restored : .purchased, isRequested: isRequested))
    }

    private func report(_ transaction: StoreTransaction) {
        deliver { transactionHandler(transaction) }
    }

    private func deliver(_ callback: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        if !isStopped {
            callback()
        }
    }
}

extension VerificationResult {
    var isUnverified: Bool {
        if case .unverified = self {
            return true
        }
        return false
    }
}

#endif
