package com.collectivelaunchpad.shop;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.collectivelaunchpad.account.AppUser;
import com.collectivelaunchpad.domain.Account;
import com.collectivelaunchpad.domain.Money;
import com.collectivelaunchpad.domain.Order;
import com.collectivelaunchpad.domain.OrderItem;
import com.collectivelaunchpad.domain.Product;
import com.collectivelaunchpad.domain.Repositories.AccountRepository;
import com.collectivelaunchpad.domain.Repositories.OrderItemRepository;
import com.collectivelaunchpad.domain.Repositories.OrderRepository;
import com.collectivelaunchpad.domain.Repositories.ProductRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.i18n.Languages;
import com.collectivelaunchpad.i18n.Msg;
import com.collectivelaunchpad.i18n.SellerLanguages;
import com.collectivelaunchpad.i18n.TranslationService;
import com.collectivelaunchpad.mail.MailService;

/**
 * Orders: placed by a logged-in customer for one shop, paid to the seller directly (cash on
 * delivery/pickup, or UPI to the seller's UPI ID). The seller accepts, prepares and confirms payment.
 */
@Service
public class OrderService {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public static class OrderException extends RuntimeException {
        public OrderException(String message) {
            super(message);
        }
    }

    public record Line(Product product, int quantity) {
        public long totalPaise() {
            return product.pricePaise == null ? 0 : product.pricePaise * quantity;
        }

        public String totalLabel() {
            return Money.label(totalPaise());
        }
    }

    /** The cart's items for one shop. */
    public record ShopCart(Seller seller, List<Line> lines) {
        public long totalPaise() {
            return lines.stream().mapToLong(Line::totalPaise).sum();
        }

        public String totalLabel() {
            return Money.label(totalPaise());
        }

        public boolean canCheckout() {
            return !lines.isEmpty() && lines.stream().allMatch(l -> l.product().canBeOrdered()
                    && (l.product().stock == null || l.product().stock >= l.quantity()));
        }
    }

    public record CheckoutForm(String name, String phone, String delivery, String address, String note, String payment) {}

    public record Earnings(long paidTotal, long paidThisMonth, long awaitingPayment, long orderCount, long activeCount) {
        public String paidTotalLabel() {
            return Money.label(paidTotal);
        }

        public String paidThisMonthLabel() {
            return Money.label(paidThisMonth);
        }

        public String awaitingPaymentLabel() {
            return Money.label(awaitingPayment);
        }
    }

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final ProductRepository products;
    private final SellerRepository sellers;
    private final AccountRepository accounts;
    private final MailService mail;
    private final Msg msg;
    private final TranslationService translator;
    private final SellerLanguages sellerLanguages;
    private final ExecutorService background;

    public OrderService(OrderRepository orders, OrderItemRepository items, ProductRepository products,
                        SellerRepository sellers, AccountRepository accounts, MailService mail, Msg msg,
                        TranslationService translator, SellerLanguages sellerLanguages, ExecutorService backgroundExecutor) {
        this.orders = orders;
        this.items = items;
        this.products = products;
        this.sellers = sellers;
        this.accounts = accounts;
        this.mail = mail;
        this.msg = msg;
        this.translator = translator;
        this.sellerLanguages = sellerLanguages;
        this.background = backgroundExecutor;
    }

    // ------------------------------------------------------------------ cart

    /** Groups the cart by shop, dropping products that no longer exist or whose shop is not live. */
    public List<ShopCart> shopCarts(Cart cart) {
        Map<Long, Integer> lines = cart.lines();
        Map<Long, List<Line>> bySeller = new java.util.LinkedHashMap<>();
        for (Product p : products.findAllById(lines.keySet())) {
            bySeller.computeIfAbsent(p.sellerId, k -> new ArrayList<>()).add(new Line(p, lines.get(p.id)));
        }
        List<ShopCart> out = new ArrayList<>();
        for (var e : bySeller.entrySet()) {
            sellers.findById(e.getKey()).filter(Seller::isPublished).ifPresent(s -> out.add(new ShopCart(s, e.getValue())));
        }
        return out;
    }

    public ShopCart shopCart(Cart cart, long sellerId) {
        return shopCarts(cart).stream().filter(c -> c.seller().id == sellerId).findFirst()
                .orElseThrow(() -> new OrderException(msg.get("err.cartNoShop")));
    }

    // ------------------------------------------------------------------ placing

    @Transactional
    public Order place(AppUser customer, Cart cart, long sellerId, CheckoutForm f) {
        ShopCart sc = shopCart(cart, sellerId);
        Seller seller = sc.seller();
        if (seller.id.equals(customer.sellerId())) {
            throw new OrderException(msg.get("err.ownShop"));
        }
        String name = required(f.name(), "err.enterName", 100);
        String phone = com.collectivelaunchpad.account.AccountService.normalizePhone(f.phone())
                .orElseThrow(() -> new OrderException(msg.get("err.mobile")));
        String delivery;
        if ("pickup".equals(f.delivery())) {
            delivery = "Pickup";
        } else {
            delivery = required(f.address(), "err.enterAddress", 500);
        }
        String payment = Order.UPI.equals(f.payment()) ? Order.UPI : Order.COD;
        if (Order.UPI.equals(payment) && (seller.upiId == null || seller.upiId.isBlank())) {
            throw new OrderException(msg.get("err.noUpi"));
        }
        String lang = Msg.lang();

        Order o = new Order();
        o.sellerId = seller.id;
        o.customerId = customer.id();
        o.customerName = name;
        o.customerPhone = phone;
        o.delivery = delivery;
        o.note = clip(f.note(), 1000);
        o.paymentMethod = payment;
        o.customerLanguage = lang;
        orders.save(o);

        long total = 0;
        for (Line l : sc.lines()) {
            Product p = products.findById(l.product().id).orElseThrow();
            if (!p.isAvailable || p.pricePaise == null) {
                throw new OrderException(msg.get("err.itemUnavailable", p.nameIn(lang)));
            }
            if (p.stock != null && products.takeStock(p.id, l.quantity()) == 0) {
                throw new OrderException(msg.get("err.onlyLeft", p.stock, p.nameIn(lang)));
            }
            OrderItem it = new OrderItem();
            it.orderId = o.id;
            it.productId = p.id;
            it.name = p.name;
            it.unit = p.unit == null ? "" : p.unit;
            it.pricePaise = p.pricePaise;
            it.quantity = l.quantity();
            items.save(it);
            total += it.lineTotalPaise();
        }
        o.totalPaise = total;
        orders.save(o);
        sc.lines().forEach(l -> cart.remove(l.product().id));
        notifySeller(o, seller, "email.newOrder", o.id, Money.label(total));
        if (o.note != null) {
            long id = o.id;
            afterCommit(() -> translateNote(id, true, sellerLanguages.of(seller)));
        }
        return o;
    }

    // ------------------------------------------------------------------ seller actions

    @Transactional
    public void sellerAction(long sellerId, long orderId, String action, String note) {
        Order o = forSeller(sellerId, orderId);
        switch (action) {
            case "accept" -> {
                requireStatus(o, Order.PLACED);
                o.status = Order.ACCEPTED;
            }
            case "decline" -> {
                requireStatus(o, Order.PLACED, Order.ACCEPTED);
                o.status = Order.DECLINED;
                o.sellerNote = clip(note, 500);
                o.sellerNoteTranslations = null;
                returnStock(o);
                if (o.sellerNote != null) {
                    long id = o.id;
                    String customerLang = customerLanguage(o);
                    afterCommit(() -> translateNote(id, false, customerLang));
                }
            }
            case "ready" -> {
                requireStatus(o, Order.ACCEPTED);
                o.status = Order.READY;
            }
            case "complete" -> {
                requireStatus(o, Order.ACCEPTED, Order.READY);
                o.status = Order.COMPLETED;
            }
            case "paid" -> {
                if (o.isClosedUnsuccessfully()) {
                    throw new OrderException(msg.get("err.orderWas", msg.get("order.status." + o.status)));
                }
                o.paymentStatus = Order.PAID;
                o.paidAt = OffsetDateTime.now();
            }
            case "unpaid" -> {
                o.paymentStatus = Order.UNPAID;
                o.paidAt = null;
            }
            default -> throw new OrderException(msg.get("err.unknownAction"));
        }
        orders.save(o);
        notifyCustomer(o);
    }

    // ------------------------------------------------------------------ customer actions

    @Transactional
    public void cancelByCustomer(long customerId, long orderId) {
        Order o = forCustomer(customerId, orderId);
        requireStatus(o, Order.PLACED);
        o.status = Order.CANCELLED;
        returnStock(o);
        orders.save(o);
        sellers.findById(o.sellerId).ifPresent(s -> notifySeller(o, s, "email.orderCancelled", o.id));
    }

    @Transactional
    public void customerMarkedPaid(long customerId, long orderId) {
        Order o = forCustomer(customerId, orderId);
        if (o.isClosedUnsuccessfully()) {
            throw new OrderException(msg.get("err.orderWas", msg.get("order.status." + o.status)));
        }
        o.customerMarkedPaidAt = OffsetDateTime.now();
        orders.save(o);
        sellers.findById(o.sellerId).ifPresent(s -> notifySeller(o, s, "email.customerPaid", o.id));
    }

    // ------------------------------------------------------------------ reading

    public Order forSeller(long sellerId, long orderId) {
        Order o = orders.findById(orderId).orElseThrow(() -> new OrderException(msg.get("err.orderNotFound")));
        if (!Objects.equals(o.sellerId, sellerId)) {
            throw new OrderException(msg.get("err.orderNotFound"));
        }
        return o;
    }

    public Order forCustomer(long customerId, long orderId) {
        Order o = orders.findById(orderId).orElseThrow(() -> new OrderException(msg.get("err.orderNotFound")));
        if (!Objects.equals(o.customerId, customerId)) {
            throw new OrderException(msg.get("err.orderNotFound"));
        }
        return o;
    }

    public Order find(long orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderException(msg.get("err.orderNotFound")));
    }

    public List<OrderItem> items(long orderId) {
        return items.findByOrderIdOrderByIdAsc(orderId);
    }

    public Map<Long, List<OrderItem>> itemsFor(List<Order> list) {
        Map<Long, List<OrderItem>> out = new java.util.HashMap<>();
        if (!list.isEmpty()) {
            items.findByOrderIdInOrderByIdAsc(list.stream().map(o -> o.id).toList())
                    .forEach(it -> out.computeIfAbsent(it.orderId, k -> new ArrayList<>()).add(it));
        }
        return out;
    }

    public ItemNames itemNames(java.util.Collection<OrderItem> list) {
        var ids = list.stream().map(it -> it.productId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Product> byId = new java.util.HashMap<>();
        products.findAllById(ids).forEach(p -> byId.put(p.id, p));
        return new ItemNames(byId);
    }

    public Earnings earnings(long sellerId) {
        List<Order> all = orders.findBySellerIdOrderByCreatedAtDesc(sellerId);
        YearMonth now = YearMonth.now(INDIA);
        long paid = 0, month = 0, awaiting = 0, active = 0;
        for (Order o : all) {
            if (o.isPaid()) {
                paid += o.totalPaise;
                if (o.paidAt != null && YearMonth.from(o.paidAt.atZoneSameInstant(INDIA)).equals(now)) {
                    month += o.totalPaise;
                }
            } else if (!o.isClosedUnsuccessfully() && !Order.PLACED.equals(o.status)) {
                awaiting += o.totalPaise;
            }
            if (o.isActive()) {
                active++;
            }
        }
        return new Earnings(paid, month, awaiting, all.size(), active);
    }

    /** UPI deep link that opens any UPI app with the amount filled in. */
    public static String upiLink(Seller s, Order o) {
        if (s.upiId == null || s.upiId.isBlank()) {
            return null;
        }
        return "upi://pay?pa=" + enc(s.upiId.strip()) + "&pn=" + enc(s.shopName == null ? s.name : s.shopName)
                + "&am=" + Money.upiAmount(o.totalPaise) + "&cu=INR&tn=" + enc("Order " + o.id);
    }

    // ------------------------------------------------------------------ helpers

    private void returnStock(Order o) {
        for (OrderItem it : items.findByOrderIdOrderByIdAsc(o.id)) {
            if (it.productId != null) {
                products.returnStock(it.productId, it.quantity);
            }
        }
    }

    private void requireStatus(Order o, String... allowed) {
        for (String a : allowed) {
            if (a.equals(o.status)) {
                return;
            }
        }
        String status = msg.get("order.status." + o.status).toLowerCase(org.springframework.context.i18n.LocaleContextHolder.getLocale());
        throw new OrderException(msg.get("err.orderAlready", status));
    }

    /** Emails the seller in their own language. */
    private void notifySeller(Order o, Seller s, String subjectKey, Object... args) {
        String lang = sellerLanguages.of(s);
        String subject = msg.in(lang, subjectKey, args);
        accounts.findBySellerId(s.id).map(a -> a.email).filter(e -> e != null && !e.isBlank())
                .ifPresent(email -> mail.send(email, null, subject,
                        subject + "\n\n" + msg.in(lang, "email.sellerOrderLink", "/seller/orders/" + o.id)));
    }

    /** Emails the customer in their own language. */
    private void notifyCustomer(Order o) {
        String lang = customerLanguage(o);
        String subject = msg.in(lang, "email.orderUpdate", o.id, msg.in(lang, "order.status." + o.status));
        accounts.findById(o.customerId).map((Account a) -> a.email).filter(e -> e != null && !e.isBlank())
                .ifPresent(email -> mail.send(email, null, subject,
                        subject + "\n\n" + msg.in(lang, "email.customerOrderLink", "/account/orders/" + o.id)));
    }

    private String customerLanguage(Order o) {
        return accounts.findById(o.customerId).map(a -> a.language).filter(Languages::isSupported)
                .orElse(Languages.orEnglish(o.customerLanguage));
    }

    /** The customer's note for the seller, or the seller's note for the customer, in the reader's language. */
    private void translateNote(long orderId, boolean customerNote, String target) {
        background.submit(() -> {
            try {
                Order o = orders.findById(orderId).orElse(null);
                String text = o == null ? null : customerNote ? o.note : o.sellerNote;
                if (text == null || (customerNote && target.equals(o.customerLanguage))) {
                    return;
                }
                var result = translator.translate(Map.of("text", text), List.of(target));
                Order fresh = orders.findById(orderId).orElseThrow();
                if (customerNote) {
                    fresh.noteTranslations = translator.flatten(result, fresh.noteTranslations);
                } else {
                    fresh.sellerNoteTranslations = translator.flatten(result, fresh.sellerNoteTranslations);
                }
                orders.save(fresh);
            } catch (Exception e) {
                log.warn("Translating the note of order {} failed: {}", orderId, e.getMessage());
            }
        });
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String required(String v, String errorKey, int max) {
        String t = clip(v, max);
        if (t == null) {
            throw new OrderException(msg.get(errorKey));
        }
        return t;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }
}
