import type { Client } from '@connectrpc/connect';
import { CoreService } from '@/gen/medusa/flow/core_service/v1/core_service_pb';

export type CoreServiceClient = Client<typeof CoreService>;
