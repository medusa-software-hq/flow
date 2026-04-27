import './App.css';

import reactLogo from './assets/react.svg';
import type { CoreServiceClient } from './grpcTypes.ts';
import { create } from '@bufbuild/protobuf';
import { GreetRequestSchema } from './gen/medusa/flow/core_service/v1/core_service_pb.ts';
import { AsyncStatus, usePromise } from './usePromise.ts';

interface AppProps {
  readonly coreServiceClient: CoreServiceClient;
}

function App(props: AppProps) {
  const { coreServiceClient } = props;

  const state = usePromise(
    () =>
      coreServiceClient.greet(
        create(GreetRequestSchema, {
          name: 'Medusa',
        }),
      ),
    [],
  );

  const buildMessage = () => {
    switch (state.status) {
      case AsyncStatus.Pending:
        return 'Loading...';
      case AsyncStatus.Fulfilled:
        return `Greeting from server: ${state.value.greeting}`;
      case AsyncStatus.Rejected:
        return `Error: ${String(state.reason)}`;
    }
  };

  return (
    <main>
      <header>
        <h2>Console</h2>
      </header>
      <img src={reactLogo} className="logo" alt="React logo" />
      <div className={'message'}>{buildMessage()}</div>
    </main>
  );
}

export default App;
