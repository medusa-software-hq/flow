import { CoreService } from '../gen/medusa/flow/core_service/v1/core_service_pb.ts';
import type { Client } from '@connectrpc/connect';

export type CoreServiceClient = Client<typeof CoreService>;
