import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { AppPage } from './pages/app/AppPage';
import { CoreServiceClient } from './rpc/myGrpcTypes';

interface RouterProps {
  readonly coreServiceClient: CoreServiceClient;
}

export function Router({ coreServiceClient }: RouterProps) {
  const router = createBrowserRouter([
    {
      path: '/',
      element: <AppPage coreServiceClient={coreServiceClient} />,
    },
  ]);

  return <RouterProvider router={router} />;
}
