import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { AppPage } from './pages/app/AppPage';

const router = createBrowserRouter([
  {
    path: '/',
    element: <AppPage />,
  },
]);

export function Router() {
  return <RouterProvider router={router} />;
}
