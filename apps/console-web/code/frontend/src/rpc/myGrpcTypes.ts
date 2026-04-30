import type { Client } from '@connectrpc/connect';
import { GrpcControlService } from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';

export type CoreServiceClient = Client<typeof GrpcControlService>;
