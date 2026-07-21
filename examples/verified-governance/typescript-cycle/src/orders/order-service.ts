import { charge } from '../billing/billing-service.js';

export function placeOrder(): string {
  return `order:${charge()}`;
}
