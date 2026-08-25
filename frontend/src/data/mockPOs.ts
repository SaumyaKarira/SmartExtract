export type POStatus = 'Processed' | 'Processing' | 'Error';

export interface POItem {
  id: number;
  description: string;
  qty: number;
  unitPrice: number;
  total: number;
}

export interface PurchaseOrder {
  poNumber: string;
  supplier: string;
  supplierAddress: string;
  orderDate: string;
  deliveryDate: string;
  paymentTerms: string;
  currency: string;
  subtotal: number;
  tax: number;
  shipping: number;
  total: number;
  status: POStatus;
  uploadedAt: string; // ISO date string for sorting
  items: POItem[];
}

export const MOCK_POS: PurchaseOrder[] = [
  {
    poNumber: 'PO-2026-0042',
    supplier: 'Acme Industrial Supplies Ltd.',
    supplierAddress: '12 Commerce Park, Suite 400, Chicago, IL 60601',
    orderDate: 'Aug 14, 2026',
    deliveryDate: 'Sep 5, 2026',
    paymentTerms: 'Net 30',
    currency: 'USD',
    subtotal: 47_250,
    tax: 3_780,
    shipping: 420,
    total: 51_450,
    status: 'Processed',
    uploadedAt: '2026-08-14',
    items: [
      { id: 1, description: 'Industrial Grade Hydraulic Pump – Model HP-450X', qty: 10, unitPrice: 2_150, total: 21_500 },
      { id: 2, description: 'Stainless Steel Valve Assembly – 2″ NPT', qty: 50, unitPrice: 185, total: 9_250 },
      { id: 3, description: 'Heavy Duty Conveyor Belt – 20m Roll', qty: 6, unitPrice: 980, total: 5_880 },
      { id: 4, description: 'Pneumatic Pressure Regulator – PR-200 Series', qty: 30, unitPrice: 124, total: 3_720 },
      { id: 5, description: 'Industrial Lubricant Oil – 20L Drums (ISO VG 68)', qty: 20, unitPrice: 145, total: 2_900 },
      { id: 6, description: 'Safety-rated Emergency Stop Button – Panel Mount', qty: 100, unitPrice: 40, total: 4_000 },
    ],
  },
  {
    poNumber: 'PO-2026-0039',
    supplier: 'TechCore Solutions Pvt. Ltd.',
    supplierAddress: '8th Floor, Cyber Tower B, Hyderabad 500081',
    orderDate: 'Aug 10, 2026',
    deliveryDate: 'Aug 28, 2026',
    paymentTerms: 'Net 15',
    currency: 'USD',
    subtotal: 28_600,
    tax: 2_288,
    shipping: 0,
    total: 30_888,
    status: 'Processed',
    uploadedAt: '2026-08-10',
    items: [
      { id: 1, description: 'Dell PowerEdge R750 Server (32-Core, 256GB RAM)', qty: 2, unitPrice: 8_500, total: 17_000 },
      { id: 2, description: '10GbE SFP+ Network Switch – 24-Port Managed', qty: 4, unitPrice: 1_850, total: 7_400 },
      { id: 3, description: 'APC Smart-UPS 3000VA Rack Mount', qty: 2, unitPrice: 1_600, total: 3_200 },
      { id: 4, description: 'Cat6A Patch Cable – 1m (50-pack)', qty: 2, unitPrice: 500, total: 1_000 },
    ],
  },
  {
    poNumber: 'PO-2026-0035',
    supplier: 'GlobalMart Procurement Co.',
    supplierAddress: '200 Harbor Blvd, Long Beach, CA 90802',
    orderDate: 'Jul 31, 2026',
    deliveryDate: 'Aug 20, 2026',
    paymentTerms: 'Net 45',
    currency: 'USD',
    subtotal: 12_400,
    tax: 992,
    shipping: 310,
    total: 13_702,
    status: 'Processed',
    uploadedAt: '2026-07-31',
    items: [
      { id: 1, description: 'Office Ergonomic Chair – Model EX-Pro', qty: 20, unitPrice: 380, total: 7_600 },
      { id: 2, description: 'Height-Adjustable Standing Desk – 180cm', qty: 10, unitPrice: 480, total: 4_800 },
    ],
  },
  {
    poNumber: 'PO-2026-0048',
    supplier: 'Nexus Pharma Distributors',
    supplierAddress: 'Plot 14, MIDC Industrial Area, Pune 411019',
    orderDate: 'Aug 18, 2026',
    deliveryDate: 'Sep 10, 2026',
    paymentTerms: 'Net 60',
    currency: 'USD',
    subtotal: 89_000,
    tax: 7_120,
    shipping: 1_100,
    total: 97_220,
    status: 'Processing',
    uploadedAt: '2026-08-18',
    items: [
      { id: 1, description: 'Sterile Vials 5ml – Borosilicate Glass (Case of 1000)', qty: 50, unitPrice: 420, total: 21_000 },
      { id: 2, description: 'Cold-Chain Packaging – Insulated Shipper Box (24hr)', qty: 200, unitPrice: 85, total: 17_000 },
      { id: 3, description: 'API Grade Ethanol – 25L (Pharma Grade)', qty: 100, unitPrice: 340, total: 34_000 },
      { id: 4, description: 'Disposable Lab Coat – XL (Box of 50)', qty: 20, unitPrice: 85, total: 1_700 },
      { id: 5, description: 'Nitrile Examination Gloves – Medium (Case of 1000)', qty: 60, unitPrice: 88, total: 5_280 },
      { id: 6, description: 'Analytical Balance – 0.1mg Precision', qty: 2, unitPrice: 5_010, total: 10_020 },
    ],
  },
  {
    poNumber: 'PO-2026-0044',
    supplier: 'BuildRight Construction Supplies',
    supplierAddress: '77 Industrial Ring Road, Atlanta, GA 30354',
    orderDate: 'Aug 15, 2026',
    deliveryDate: 'Sep 1, 2026',
    paymentTerms: 'Net 30',
    currency: 'USD',
    subtotal: 34_750,
    tax: 2_780,
    shipping: 890,
    total: 38_420,
    status: 'Error',
    uploadedAt: '2026-08-15',
    items: [
      { id: 1, description: 'Portland Cement – 50kg Bags (Pallet of 40)', qty: 20, unitPrice: 320, total: 6_400 },
      { id: 2, description: 'Reinforcement Steel Rebar – 12mm dia, 6m lengths', qty: 500, unitPrice: 28, total: 14_000 },
      { id: 3, description: 'Waterproof Membrane – 1.5mm HDPE, 100m²', qty: 30, unitPrice: 480, total: 14_400 },
    ],
  },
  {
    poNumber: 'PO-2026-0031',
    supplier: 'ABC Technologies Inc.',
    supplierAddress: '1 Infinite Loop, Cupertino, CA 95014',
    orderDate: 'Jul 20, 2026',
    deliveryDate: 'Aug 5, 2026',
    paymentTerms: 'Net 30',
    currency: 'USD',
    subtotal: 64_200,
    tax: 5_136,
    shipping: 0,
    total: 69_336,
    status: 'Processed',
    uploadedAt: '2026-07-20',
    items: [
      { id: 1, description: 'MacBook Pro 16" M4 Max – 64GB/2TB', qty: 15, unitPrice: 3_200, total: 48_000 },
      { id: 2, description: 'Apple Studio Display – 27" 5K Retina', qty: 15, unitPrice: 1_080, total: 16_200 },
    ],
  },
  {
    poNumber: 'PO-2026-0027',
    supplier: 'SwiftLog Freight Partners',
    supplierAddress: 'Warehouse 9, Logistics Hub, Mumbai 400070',
    orderDate: 'Jul 10, 2026',
    deliveryDate: 'Jul 25, 2026',
    paymentTerms: 'Net 15',
    currency: 'USD',
    subtotal: 8_500,
    tax: 680,
    shipping: 250,
    total: 9_430,
    status: 'Processed',
    uploadedAt: '2026-07-10',
    items: [
      { id: 1, description: 'Freight Forwarding Service – Air (FCL, 500kg)', qty: 1, unitPrice: 5_800, total: 5_800 },
      { id: 2, description: 'Customs Clearance & Documentation', qty: 1, unitPrice: 1_200, total: 1_200 },
      { id: 3, description: 'Last-Mile Delivery – Metro Zone', qty: 1, unitPrice: 1_500, total: 1_500 },
    ],
  },
  {
    poNumber: 'PO-2026-0051',
    supplier: 'GreenEnergy Renewables Ltd.',
    supplierAddress: 'Solar Park, Rajkot, Gujarat 360003',
    orderDate: 'Aug 19, 2026',
    deliveryDate: 'Oct 15, 2026',
    paymentTerms: 'Net 90',
    currency: 'USD',
    subtotal: 185_000,
    tax: 14_800,
    shipping: 3_200,
    total: 203_000,
    status: 'Processing',
    uploadedAt: '2026-08-19',
    items: [
      { id: 1, description: 'Mono-PERC Solar Panel – 550W (Tier-1)', qty: 200, unitPrice: 420, total: 84_000 },
      { id: 2, description: 'String Inverter – 50kW 3-Phase Grid-Tie', qty: 10, unitPrice: 6_800, total: 68_000 },
      { id: 3, description: 'Mounting Structure – Aluminum Racking System (per kWp)', qty: 110, unitPrice: 280, total: 30_800 },
      { id: 4, description: 'DC Cable – 6mm² PV Solar (100m Reel)', qty: 20, unitPrice: 110, total: 2_200 },
    ],
  },
];

