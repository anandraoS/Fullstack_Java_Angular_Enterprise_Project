import { setupServer } from 'msw/node';

/** Absolute origin (Node's fetch cannot resolve relative URLs); config is pointed here in setup.ts. */
export const TEST_API = 'http://oms.test/api/v1';
export const url = (path: string): string => `${TEST_API}${path}`;

export const server = setupServer();
