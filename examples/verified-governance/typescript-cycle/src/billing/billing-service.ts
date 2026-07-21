import { placeOrder } from '../orders/order-service.js';

export function charge(): string {
  return 'paid';
}

export function canAudit(): boolean {
  return typeof placeOrder === 'function';
}
