import { createClient } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import { CoreService } from '@/gen/medusa/flow/core_service/v1/core_service_pb';
import { CoreServiceClient } from './myGrpcTypes';

export function createCoreServiceClient(): CoreServiceClient {
  const baseUrl = import.meta.env.VITE_CORE_SERVICE_URL;

  if (baseUrl === undefined) {
    throw new Error('VITE_CORE_SERVICE_URL is not defined');
  }

  const transport = createGrpcWebTransport({
    baseUrl,
  });

  return createClient(CoreService, transport);
}
