package com.collectivelaunchpad.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repositories: parameterised queries only. */
public final class Repositories {

    private Repositories() {}

    public interface GroupRepository extends JpaRepository<Group, Long> {
        Optional<Group> findFirstByOrderByIdAsc();
    }

    public interface AccountRepository extends JpaRepository<Account, Long> {
        Optional<Account> findByEmailIgnoreCase(String email);

        Optional<Account> findByPhone(String phone);

        Optional<Account> findBySellerId(Long sellerId);

        List<Account> findAllByOrderByCreatedAtDesc();

        boolean existsByPhone(String phone);

        boolean existsByEmailIgnoreCase(String email);
    }

    public interface OrderRepository extends JpaRepository<Order, Long> {
        List<Order> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

        List<Order> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

        List<Order> findTop100ByOrderByCreatedAtDesc();

        long countBySellerIdAndStatus(Long sellerId, String status);

        long countByStatus(String status);
    }

    public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
        List<OrderItem> findByOrderIdOrderByIdAsc(Long orderId);

        List<OrderItem> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);
    }

    public interface SellerRepository extends JpaRepository<Seller, Long> {
        Optional<Seller> findBySlug(String slug);

        Optional<Seller> findBySlugAndStatus(String slug, String status);

        boolean existsBySlugAndIdNot(String slug, Long id);

        List<Seller> findByStatusOrderByShopNameAsc(String status);

        List<Seller> findAllByOrderByCreatedAtDesc();

        long countByStatus(String status);
    }

    public interface ProductRepository extends JpaRepository<Product, Long> {
        List<Product> findBySellerIdOrderBySortOrderAscIdAsc(Long sellerId);

        List<Product> findBySellerIdAndIsAvailableTrueOrderBySortOrderAscIdAsc(Long sellerId);

        long countBySellerId(Long sellerId);

        Optional<Product> findByIdAndSellerId(Long id, Long sellerId);

        List<Product> findBySellerIdInAndIsAvailableTrueOrderBySellerIdAscSortOrderAscIdAsc(Collection<Long> sellerIds);

        /** Takes stock only if enough is left (or stock is not counted). Returns 1 on success, 0 if not enough. */
        @org.springframework.data.jpa.repository.Modifying
        @org.springframework.data.jpa.repository.Query(
                "update Product p set p.stock = p.stock - :qty where p.id = :id and p.stock is not null and p.stock >= :qty")
        int takeStock(@org.springframework.data.repository.query.Param("id") long id,
                      @org.springframework.data.repository.query.Param("qty") int qty);

        @org.springframework.data.jpa.repository.Modifying
        @org.springframework.data.jpa.repository.Query(
                "update Product p set p.stock = p.stock + :qty where p.id = :id and p.stock is not null")
        int returnStock(@org.springframework.data.repository.query.Param("id") long id,
                        @org.springframework.data.repository.query.Param("qty") int qty);
    }

    public interface OnboardingSessionRepository extends JpaRepository<OnboardingSession, Long> {
        List<OnboardingSession> findByStatusInOrderByUpdatedAtDesc(Collection<String> statuses);

        Optional<OnboardingSession> findFirstBySellerIdOrderByIdDesc(Long sellerId);
    }

    public interface EnquiryRepository extends JpaRepository<Enquiry, Long> {
        List<Enquiry> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses);

        List<Enquiry> findTop30ByStatusInOrderByCreatedAtDesc(Collection<String> statuses);

        long countByStatusIn(Collection<String> statuses);

        List<Enquiry> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

        List<Enquiry> findByCustomerUserIdOrderByCreatedAtDesc(Long customerUserId);

        long countBySellerIdAndStatusIn(Long sellerId, Collection<String> statuses);
    }

    public interface ReplyRepository extends JpaRepository<Reply, Long> {
        List<Reply> findByEnquiryIdOrderByIdDesc(Long enquiryId);

        Optional<Reply> findFirstByEnquiryIdAndStatusInOrderByIdDesc(Long enquiryId, Collection<String> statuses);
    }

    public interface GroupRequestRepository extends JpaRepository<GroupRequest, Long> {
        List<GroupRequest> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses);

        List<GroupRequest> findTop50ByStatusInOrderByCreatedAtDesc(Collection<String> statuses);

        long countByStatusIn(Collection<String> statuses);
    }

    public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {
        List<EmailLog> findTop10ByOrderByIdDesc();
    }
}
