package org.example.export;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.entity.PurchaseOrder;
import org.example.entity.PurchaseOrderItem;
import org.example.purchaseorder.PurchaseOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final PurchaseOrderRepository purchaseOrderRepository;

    public ExportService(PurchaseOrderRepository purchaseOrderRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
    }

    // -------------------------------------------------------------------------
    // CSV
    // -------------------------------------------------------------------------

    public byte[] exportCsv(Long userId) throws IOException {
        List<PurchaseOrder> orders = purchaseOrderRepository.findByUserId(userId);

        StringBuilder sb = new StringBuilder();
        sb.append("PO Number,Vendor,PO Date,Payment Terms,Total Amount,Status,")
          .append("Item #,Item Description,Quantity,Unit Price,Item Total\n");

        for (PurchaseOrder po : orders) {
            String poNumber = csv(po.getPoNumber());
            String vendor   = csv(po.getSupplier());
            String date     = po.getOrderDate() != null ? po.getOrderDate().format(DATE_FMT) : "";
            String terms    = csv(po.getPaymentTerms());
            String total    = po.getTotal() != null ? po.getTotal().toPlainString() : "";
            String status   = po.getDocument() != null ? po.getDocument().getStatus().name() : "";

            List<PurchaseOrderItem> items = po.getItems();
            if (items.isEmpty()) {
                sb.append(poNumber).append(',').append(vendor).append(',')
                  .append(date).append(',').append(terms).append(',')
                  .append(total).append(',').append(status).append(',')
                  .append(",,,,\n");
            } else {
                for (int i = 0; i < items.size(); i++) {
                    PurchaseOrderItem item = items.get(i);
                    sb.append(poNumber).append(',').append(vendor).append(',')
                      .append(date).append(',').append(terms).append(',')
                      .append(total).append(',').append(status).append(',')
                      .append(i + 1).append(',')
                      .append(csv(item.getDescription())).append(',')
                      .append(item.getQuantity() != null ? item.getQuantity().toPlainString() : "").append(',')
                      .append(item.getUnitPrice() != null ? item.getUnitPrice().toPlainString() : "").append(',')
                      .append(item.getTotalPrice() != null ? item.getTotalPrice().toPlainString() : "").append('\n');
                }
            }
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Excel (.xlsx)
    // -------------------------------------------------------------------------

    public byte[] exportXlsx(Long userId) throws IOException {
        List<PurchaseOrder> orders = purchaseOrderRepository.findByUserId(userId);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            // ── Summary sheet ──────────────────────────────────────────────
            Sheet summary = wb.createSheet("Purchase Orders");
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle amountStyle = createAmountStyle(wb);
            CellStyle altStyle    = createAltRowStyle(wb);

            String[] headers = {"PO Number", "Vendor", "PO Date", "Payment Terms",
                                 "Total Amount", "Status"};
            org.apache.poi.ss.usermodel.Row hRow = summary.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (PurchaseOrder po : orders) {
                org.apache.poi.ss.usermodel.Row row = summary.createRow(rowIdx);
                CellStyle rowStyle = rowIdx % 2 == 0 ? altStyle : null;
                poiSetCell(row, 0, po.getPoNumber(), rowStyle);
                poiSetCell(row, 1, po.getSupplier(), rowStyle);
                poiSetCell(row, 2, po.getOrderDate() != null ? po.getOrderDate().format(DATE_FMT) : "", rowStyle);
                poiSetCell(row, 3, po.getPaymentTerms(), rowStyle);
                Cell amtCell = row.createCell(4);
                if (po.getTotal() != null) {
                    amtCell.setCellValue(po.getTotal().doubleValue());
                    amtCell.setCellStyle(amountStyle);
                }
                poiSetCell(row, 5, po.getDocument() != null ? po.getDocument().getStatus().name() : "", rowStyle);
                rowIdx++;
            }
            for (int i = 0; i < headers.length; i++) summary.autoSizeColumn(i);

            // ── Line Items sheet ───────────────────────────────────────────
            Sheet itemsSheet = wb.createSheet("Line Items");
            String[] itemHeaders = {"PO Number", "Vendor", "Item #", "Description",
                                     "Quantity", "Unit Price", "Item Total"};
            org.apache.poi.ss.usermodel.Row ihRow = itemsSheet.createRow(0);
            for (int i = 0; i < itemHeaders.length; i++) {
                Cell c = ihRow.createCell(i);
                c.setCellValue(itemHeaders[i]);
                c.setCellStyle(headerStyle);
            }
            int iRow = 1;
            for (PurchaseOrder po : orders) {
                int itemNum = 1;
                for (PurchaseOrderItem item : po.getItems()) {
                    org.apache.poi.ss.usermodel.Row row = itemsSheet.createRow(iRow++);
                    CellStyle rs = iRow % 2 == 0 ? altStyle : null;
                    poiSetCell(row, 0, po.getPoNumber(), rs);
                    poiSetCell(row, 1, po.getSupplier(), rs);
                    row.createCell(2).setCellValue(itemNum++);
                    poiSetCell(row, 3, item.getDescription(), rs);
                    if (item.getQuantity() != null) row.createCell(4).setCellValue(item.getQuantity().doubleValue());
                    if (item.getUnitPrice() != null) {
                        Cell c = row.createCell(5);
                        c.setCellValue(item.getUnitPrice().doubleValue());
                        c.setCellStyle(amountStyle);
                    }
                    if (item.getTotalPrice() != null) {
                        Cell c = row.createCell(6);
                        c.setCellValue(item.getTotalPrice().doubleValue());
                        c.setCellStyle(amountStyle);
                    }
                }
            }
            for (int i = 0; i < itemHeaders.length; i++) itemsSheet.autoSizeColumn(i);

            wb.write(baos);
            return baos.toByteArray();
        }
    }

    // -------------------------------------------------------------------------
    // PDF
    // -------------------------------------------------------------------------

    public byte[] exportPdf(Long userId) throws IOException {
        List<PurchaseOrder> orders = purchaseOrderRepository.findByUserId(userId);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 60, 40);
        try {
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD, new Color(30, 58, 138));
            Font headFont  = new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE);
            Font labelFont = new Font(Font.HELVETICA, 9,  Font.BOLD, new Color(75, 85, 99));
            Font valueFont = new Font(Font.HELVETICA, 9,  Font.NORMAL, new Color(17, 24, 39));
            Font smallGray = new Font(Font.HELVETICA, 8,  Font.NORMAL, new Color(107, 114, 128));
            Font itemHead  = new Font(Font.HELVETICA, 8,  Font.BOLD, Color.WHITE);
            Font itemVal   = new Font(Font.HELVETICA, 8,  Font.NORMAL, new Color(17, 24, 39));
            Font totalFont = new Font(Font.HELVETICA, 9,  Font.BOLD, new Color(30, 58, 138));

            Color headerBg   = new Color(30, 58, 138);
            Color altRowBg   = new Color(239, 246, 255);
            Color borderGray = new Color(209, 213, 219);

            Paragraph reportTitle = new Paragraph("SmartExtract — Purchase Order Report", titleFont);
            reportTitle.setAlignment(Element.ALIGN_CENTER);
            reportTitle.setSpacingAfter(4);
            doc.add(reportTitle);

            Paragraph sub = new Paragraph(
                "Generated " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
                    + "  •  " + orders.size() + " purchase order" + (orders.size() != 1 ? "s" : ""),
                smallGray);
            sub.setAlignment(Element.ALIGN_CENTER);
            sub.setSpacingAfter(16);
            doc.add(sub);

            for (PurchaseOrder po : orders) {
                PdfPTable poHeader = new PdfPTable(2);
                poHeader.setWidthPercentage(100);
                poHeader.setSpacingBefore(8);
                poHeader.setSpacingAfter(0);
                poHeader.setWidths(new float[]{3, 1});

                String poNum = po.getPoNumber() != null ? po.getPoNumber() : "PO #" + po.getId();
                PdfPCell titleCell = new PdfPCell(new Phrase(poNum, new Font(Font.HELVETICA, 12, Font.BOLD, Color.WHITE)));
                titleCell.setBackgroundColor(headerBg);
                titleCell.setPadding(8);
                titleCell.setBorder(Rectangle.NO_BORDER);
                poHeader.addCell(titleCell);

                String status = po.getDocument() != null ? po.getDocument().getStatus().name() : "";
                PdfPCell statusCell = new PdfPCell(new Phrase(status, headFont));
                statusCell.setBackgroundColor(headerBg);
                statusCell.setPadding(8);
                statusCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                statusCell.setBorder(Rectangle.NO_BORDER);
                poHeader.addCell(statusCell);
                doc.add(poHeader);

                PdfPTable details = new PdfPTable(4);
                details.setWidthPercentage(100);
                details.setSpacingBefore(0);
                details.setSpacingAfter(4);
                details.setWidths(new float[]{1.2f, 2f, 1.2f, 2f});

                addDetailCell(details, "Vendor",        po.getSupplier(),           labelFont, valueFont, borderGray);
                addDetailCell(details, "PO Date",       fmtDate(po.getOrderDate()), labelFont, valueFont, borderGray);
                addDetailCell(details, "Payment Terms", po.getPaymentTerms(),       labelFont, valueFont, borderGray);
                addDetailCell(details, "Total Amount",  fmtAmount(po.getTotal()),   labelFont, totalFont, borderGray);
                doc.add(details);

                if (!po.getItems().isEmpty()) {
                    PdfPTable itemTable = new PdfPTable(5);
                    itemTable.setWidthPercentage(100);
                    itemTable.setSpacingBefore(2);
                    itemTable.setSpacingAfter(8);
                    itemTable.setWidths(new float[]{0.4f, 3.5f, 0.8f, 1.2f, 1.2f});

                    for (String h : new String[]{"#", "Description", "Qty", "Unit Price", "Total"}) {
                        PdfPCell hc = new PdfPCell(new Phrase(h, itemHead));
                        hc.setBackgroundColor(new Color(79, 70, 229));
                        hc.setPadding(4);
                        hc.setBorderColor(borderGray);
                        itemTable.addCell(hc);
                    }

                    int idx = 1;
                    for (PurchaseOrderItem item : po.getItems()) {
                        Color bg = idx % 2 == 0 ? altRowBg : Color.WHITE;
                        addItemCell(itemTable, String.valueOf(idx),                                                              itemVal, bg, borderGray, Element.ALIGN_CENTER);
                        addItemCell(itemTable, item.getDescription() != null ? item.getDescription() : "—",                    itemVal, bg, borderGray, Element.ALIGN_LEFT);
                        addItemCell(itemTable, item.getQuantity() != null ? item.getQuantity().stripTrailingZeros().toPlainString() : "—", itemVal, bg, borderGray, Element.ALIGN_RIGHT);
                        addItemCell(itemTable, fmtAmount(item.getUnitPrice()),                                                  itemVal, bg, borderGray, Element.ALIGN_RIGHT);
                        addItemCell(itemTable, fmtAmount(item.getTotalPrice()),                                                 itemVal, bg, borderGray, Element.ALIGN_RIGHT);
                        idx++;
                    }
                    doc.add(itemTable);
                } else {
                    Paragraph noItems = new Paragraph("No line items extracted.", smallGray);
                    noItems.setSpacingAfter(8);
                    doc.add(noItems);
                }

                doc.add(new Chunk(new com.lowagie.text.pdf.draw.LineSeparator(
                        0.5f, 100, borderGray, Element.ALIGN_CENTER, -2)));
            }
        } finally {
            if (doc.isOpen()) doc.close();
        }
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void addDetailCell(PdfPTable table, String label, String value,
                                Font lf, Font vf, Color border) {
        PdfPCell lc = new PdfPCell(new Phrase(label, lf));
        lc.setPadding(5); lc.setBorderColor(border);
        lc.setBackgroundColor(new Color(249, 250, 251));
        table.addCell(lc);
        PdfPCell vc = new PdfPCell(new Phrase(value != null ? value : "—", vf));
        vc.setPadding(5); vc.setBorderColor(border);
        table.addCell(vc);
    }

    private void addItemCell(PdfPTable table, String text, Font font,
                              Color bg, Color border, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(bg);
        c.setPadding(4);
        c.setBorderColor(border);
        c.setHorizontalAlignment(align);
        table.addCell(c);
    }

    private String fmtDate(LocalDate d) {
        return d != null ? d.format(DATE_FMT) : "—";
    }

    private String fmtAmount(BigDecimal v) {
        return v != null ? v.toPlainString() : "—";
    }

    private String csv(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }

    private CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }

    private CellStyle createAmountStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        DataFormat fmt = wb.createDataFormat();
        style.setDataFormat(fmt.getFormat("#,##0.00"));
        return style;
    }

    private CellStyle createAltRowStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private void poiSetCell(org.apache.poi.ss.usermodel.Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        if (style != null) c.setCellStyle(style);
    }
}

