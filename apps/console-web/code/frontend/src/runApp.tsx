import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import App from './App.tsx';
import { CoreService } from './gen/medusa/flow/core_service/v1/core_service_pb.ts';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import { createClient } from '@connectrpc/connect';
import type { CoreServiceClient } from './grpcTypes.ts';

export function runApp() {
  const root = document.getElementById('root');
  if (root === null) throw new Error('Root element not found');

  const coreServiceUrl: unknown = import.meta.env.VITE_CORE_SERVICE_URL;
  if (typeof coreServiceUrl !== 'string')
    throw new Error('VITE_CORE_SERVICE_URL is not defined');

  const grpcTransport = createGrpcWebTransport({
    baseUrl: coreServiceUrl,
  });

  const coreServiceClient: CoreServiceClient = createClient(
    CoreService,
    grpcTransport,
  );

  createRoot(root).render(
    <StrictMode>
      <App coreServiceClient={coreServiceClient} />
    </StrictMode>,
  );
}
