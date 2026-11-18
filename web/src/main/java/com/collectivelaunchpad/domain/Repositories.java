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

    public interface AdminRepository extends JpaRepository<Admin, Long> {
        Optional<Admin> findByEmailIgnoreCase(String email);
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
    }

    public interface OnboardingSessionRepository extends JpaRepository<OnboardingSession, Long> {
        List<OnboardingSession> findByStatusInOrderByUpdatedAtDesc(Collection<String> statuses);

        Optional<OnboardingSession> findFirstBySellerIdOrderByIdDesc(Long sellerId);
    }

    public interface EnquiryRepository extends JpaRepository<Enquiry, Long> {
        List<Enquiry> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses);

        List<Enquiry> findTop30ByStatusInOrderByCreatedAtDesc(Collection<String> statuses);

        long countByStatusIn(Collection<String> statuses);
    }

    public interface ReplyRepository extends JpaRepository<Reply, Long> {
        List<Reply> findByEnquiryIdOrderByIdDesc(Long enquiryId);

        Optional<Reply> findFirstByEnquiryIdAndStatusInOrderByIdDesc(Long enquiryId, Collection<String> statuses);
    }

    public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {
        List<EmailLog> findTop10ByOrderByIdDesc();
    }
}
