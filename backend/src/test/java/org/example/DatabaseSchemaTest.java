package org.example;

import org.example.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class DatabaseSchemaTest {

    @Autowired
    private TestEntityManager em;

    @Test
    void shouldPersistUserAndRelatedEntities() {
        // Create User
        User user = new User();
        user.setName("Test User");
        user.setEmail("test@example.com");
        user.setPasswordHash("hashed_password");
        user.setCreatedAt(LocalDateTime.now());
        em.persist(user);

        // Create Document
        Document doc = new Document();
        doc.setUser(user);
        doc.setFileName("invoice.pdf");
        doc.setFileType("application/pdf");
        doc.setStatus(DocumentStatus.UPLOADED);
        doc.setUploadedAt(LocalDateTime.now());
        em.persist(doc);

        // Create PurchaseOrder
        PurchaseOrder po = new PurchaseOrder();
        po.setUser(user);
        po.setDocument(doc);
        po.setPoNumber("PO-001");
        po.setSupplier("Acme Corp");
        po.setOrderDate(LocalDate.now());
        po.setDeliveryDate(LocalDate.now().plusDays(30));
        po.setPaymentTerms("Net 30");
        po.setCurrency("USD");
        po.setSubtotal(new BigDecimal("100.00"));
        po.setTax(new BigDecimal("10.00"));
        po.setTotal(new BigDecimal("110.00"));
        po.setCreatedAt(LocalDateTime.now());
        em.persist(po);

        // Create PurchaseOrderItem
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setPurchaseOrder(po);
        item.setDescription("Widget A");
        item.setQuantity(new BigDecimal("2.00"));
        item.setUnitPrice(new BigDecimal("50.00"));
        item.setTotalPrice(new BigDecimal("100.00"));
        em.persist(item);

        em.flush();
        em.clear();

        // Verify persistence
        User foundUser = em.find(User.class, user.getId());
        assertThat(foundUser).isNotNull();
        assertThat(foundUser.getEmail()).isEqualTo("test@example.com");

        Document foundDoc = em.find(Document.class, doc.getId());
        assertThat(foundDoc.getStatus()).isEqualTo(DocumentStatus.UPLOADED);

        PurchaseOrder foundPo = em.find(PurchaseOrder.class, po.getId());
        assertThat(foundPo.getPoNumber()).isEqualTo("PO-001");
        assertThat(foundPo.getTotal()).isEqualByComparingTo("110.00");

        PurchaseOrderItem foundItem = em.find(PurchaseOrderItem.class, item.getId());
        assertThat(foundItem.getDescription()).isEqualTo("Widget A");
    }
}

