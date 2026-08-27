package org.example.search;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.example.entity.DocumentStatus;
import org.example.entity.PurchaseOrder;
import org.example.entity.PurchaseOrderItem;
import org.example.purchaseorder.PurchaseOrderResponse;
import org.example.purchaseorder.PurchaseOrderResponse.PurchaseOrderItemResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class PurchaseOrderSearchService {

    private final EntityManager em;

    public PurchaseOrderSearchService(EntityManager em) {
        this.em = em;
    }

    public SearchResponse search(SearchQuery q, Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        // ── Count query ────────────────────────────────────────────────────
        CriteriaQuery<Long> countCq = cb.createQuery(Long.class);
        Root<PurchaseOrder> countRoot = countCq.from(PurchaseOrder.class);
        List<Predicate> countPredicates = buildPredicates(cb, countRoot, countCq, q, userId);
        countCq.select(cb.countDistinct(countRoot)).where(countPredicates.toArray(new Predicate[0]));
        long total = em.createQuery(countCq).getSingleResult();

        // ── Results query ──────────────────────────────────────────────────
        CriteriaQuery<PurchaseOrder> cq = cb.createQuery(PurchaseOrder.class);
        Root<PurchaseOrder> root = cq.from(PurchaseOrder.class);
        root.fetch("items", JoinType.LEFT);

        List<Predicate> predicates = buildPredicates(cb, root, cq, q, userId);
        cq.select(root).distinct(true).where(predicates.toArray(new Predicate[0]));

        // Sort — nulls last so POs without a total don't float to the top
        String sortBy = q.sortBy() != null ? q.sortBy() : "date";
        String sortDir = q.sortDir() != null ? q.sortDir() : "desc";
        Expression<?> sortExpr = switch (sortBy) {
            case "amount"    -> root.get("total");
            case "poNumber"  -> root.get("poNumber");
            case "supplier"  -> root.get("supplier");
            default          -> root.get("createdAt");
        };
        Order order = "asc".equals(sortDir) ? cb.asc(sortExpr) : cb.desc(sortExpr);
        // Push NULLs to the end regardless of sort direction
        cq.orderBy(order, cb.asc(root.get("id")));

        int page = Math.max(0, q.page());
        int pageSize = (q.pageSize() > 0 && q.pageSize() <= 100) ? q.pageSize() : 20;

        TypedQuery<PurchaseOrder> tq = em.createQuery(cq)
                .setFirstResult(page * pageSize)
                .setMaxResults(pageSize);

        List<PurchaseOrderResponse> results = tq.getResultList().stream()
                .map(po -> toResponse(po, true))
                .toList();

        String parsedDesc = buildDescription(q);

        return new SearchResponse(parsedDesc, null, (int) total, page, pageSize, results);
    }

    private List<Predicate> buildPredicates(CriteriaBuilder cb,
                                             Root<PurchaseOrder> root,
                                             CriteriaQuery<?> cq,
                                             SearchQuery q,
                                             Long userId) {
        List<Predicate> predicates = new ArrayList<>();

        // Always scope to authenticated user
        predicates.add(cb.equal(root.get("user").get("id"), userId));

        if (q.poNumber() != null && !q.poNumber().isBlank()) {
            predicates.add(cb.like(cb.lower(root.get("poNumber")),
                    "%" + q.poNumber().toLowerCase() + "%"));
        }

        if (q.supplier() != null && !q.supplier().isBlank()) {
            predicates.add(cb.like(cb.lower(root.get("supplier")),
                    "%" + q.supplier().toLowerCase() + "%"));
        }

        if (q.minAmount() != null) {
            predicates.add(q.amountInclusive()
                    ? cb.greaterThanOrEqualTo(root.get("total"), q.minAmount())
                    : cb.greaterThan(root.get("total"), q.minAmount()));
        }
        if (q.maxAmount() != null) {
            predicates.add(q.amountInclusive()
                    ? cb.lessThanOrEqualTo(root.get("total"), q.maxAmount())
                    : cb.lessThan(root.get("total"), q.maxAmount()));
        }

        if (q.dateFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("orderDate"), q.dateFrom()));
        }
        if (q.dateTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("orderDate"), q.dateTo()));
        }

        if (q.status() != null && !q.status().isBlank()) {
            Join<?, ?> docJoin = root.join("document", JoinType.LEFT);
            if ("COMPLETED_ANY".equals(q.status())) {
                // Match both COMPLETED and COMPLETED_WITH_CORRECTIONS
                predicates.add(docJoin.get("status").in(
                        DocumentStatus.COMPLETED,
                        DocumentStatus.COMPLETED_WITH_CORRECTIONS
                ));
            } else {
                predicates.add(cb.equal(docJoin.get("status"),
                        DocumentStatus.valueOf(q.status())));
            }
        }

        if (q.itemDescription() != null && !q.itemDescription().isBlank()) {
            // Subquery: PO has at least one item matching the description
            Subquery<Long> sub = cq.subquery(Long.class);
            Root<PurchaseOrderItem> itemRoot = sub.from(PurchaseOrderItem.class);
            sub.select(itemRoot.get("purchaseOrder").get("id"))
               .where(cb.and(
                   cb.equal(itemRoot.get("purchaseOrder").get("id"), root.get("id")),
                   cb.like(cb.lower(itemRoot.get("description")),
                           "%" + q.itemDescription().toLowerCase() + "%")
               ));
            predicates.add(cb.exists(sub));
        }

        return predicates;
    }

    /** Returns distinct non-null supplier names for the given user, sorted alphabetically. */
    public List<String> distinctSuppliers(Long userId) {
        return em.createQuery(
                "SELECT DISTINCT po.supplier FROM PurchaseOrder po " +
                "WHERE po.user.id = :uid AND po.supplier IS NOT NULL AND po.supplier <> '' " +
                "ORDER BY po.supplier", String.class)
                .setParameter("uid", userId)
                .getResultList();
    }

    private String buildDescription(SearchQuery q) {
        List<String> parts = new ArrayList<>();
        if (q.poNumber() != null)        parts.add("PO number contains \"" + q.poNumber() + "\"");
        if (q.supplier() != null)        parts.add("supplier contains \"" + q.supplier() + "\"");
        if (q.itemDescription() != null) parts.add("item contains \"" + q.itemDescription() + "\"");
        if (q.minAmount() != null && q.maxAmount() != null)
            parts.add("amount between ₹" + q.minAmount() + " and ₹" + q.maxAmount());
        else if (q.minAmount() != null)  parts.add("amount " + (q.amountInclusive() ? "≥" : ">") + " ₹" + q.minAmount());
        else if (q.maxAmount() != null)  parts.add("amount " + (q.amountInclusive() ? "≤" : "<") + " ₹" + q.maxAmount());
        if (q.dateFrom() != null && q.dateTo() != null)
            parts.add("date between " + q.dateFrom() + " and " + q.dateTo());
        else if (q.dateFrom() != null)   parts.add("date from " + q.dateFrom());
        else if (q.dateTo() != null)     parts.add("date up to " + q.dateTo());
        if (q.status() != null) {
            String statusLabel = switch (q.status()) {
                case "COMPLETED_ANY" -> "Completed";
                case "NEEDS_REVIEW"  -> "Needs Review";
                case "FAILED"        -> "Failed";
                case "PROCESSING"    -> "Processing";
                default              -> q.status();
            };
            parts.add("status = " + statusLabel);
        }
        String base = parts.isEmpty() ? "all purchase orders" : String.join(", ", parts);
        // Append sort description
        if ("amount".equals(q.sortBy())) {
            String label = "desc".equals(q.sortDir()) ? "largest" : "smallest";
            if (q.pageSize() <= 10) {
                base = "Top " + q.pageSize() + " " + label + (parts.isEmpty() ? " purchase orders" : " matching");
            } else {
                base += " — sorted by " + label + " first";
            }
        }
        return base;
    }

    private PurchaseOrderResponse toResponse(PurchaseOrder po, boolean includeItems) {
        List<PurchaseOrderItemResponse> items = includeItems
                ? po.getItems().stream().map(i -> new PurchaseOrderItemResponse(
                        i.getId(), i.getDescription(), i.getQuantity(), i.getUnitPrice(), i.getTotalPrice()
                  )).toList()
                : List.of();

        String status = po.getDocument() != null && po.getDocument().getStatus() != null
                ? po.getDocument().getStatus().name() : null;

        return new PurchaseOrderResponse(
                po.getId(), po.getUser().getId(), po.getDocument().getId(),
                po.getDocument().getFileName(),
                po.getPoNumber(), po.getSupplier(), po.getOrderDate(), po.getDeliveryDate(),
                po.getPaymentTerms(), po.getCurrency(), po.getSubtotal(), po.getTax(),
                po.getTotal(), po.getCreatedAt(), items, status,
                po.getValidationCorrections(), po.getValidationReviewReasons(), null, null);
    }
}

