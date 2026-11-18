package com.collectivelaunchpad.domain;

/**
 * Status values stored in the database. Entities use plain foreign-key ids rather than JPA
 * associations, so there are no lazy proxies and public fields are safe to use.
 */
public final class Entities {

    private Entities() {}

    public static final class SellerStatus {
        public static final String DRAFT = "draft";
        public static final String PENDING_REVIEW = "pending_review";
        public static final String PUBLISHED = "published";
        public static final String HIDDEN = "hidden";

        private SellerStatus() {}
    }

    public static final class SessionStatus {
        public static final String WORKING = "working";
        public static final String NEEDS_INPUT = "needs_input";
        public static final String SUBMITTED = "submitted";
        public static final String FAILED = "failed";

        private SessionStatus() {}
    }

    public static final class EnquiryStatus {
        public static final String NEW = "new";
        public static final String DRAFTED = "drafted";
        public static final String REPLIED = "replied";
        public static final String CLOSED = "closed";

        private EnquiryStatus() {}
    }

    public static final class ReplyStatus {
        public static final String DRAFT = "draft";
        public static final String APPROVED = "approved";
        public static final String SENT = "sent";
        public static final String DISCARDED = "discarded";

        private ReplyStatus() {}
    }
}
