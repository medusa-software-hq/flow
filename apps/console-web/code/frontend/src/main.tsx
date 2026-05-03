import 'core-js/actual';
import './global.css';
import ReactDOM from 'react-dom/client';
import RootComponent from './RootComponent';

const root = document.getElementById('root');
if (root === null) {
  throw new Error('Root element not found');
}

ReactDOM.createRoot(root).render(<RootComponent />);
