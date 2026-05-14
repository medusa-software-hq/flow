import { Center, Image } from '@mantine/core';
import crashImageUrl from '../../../../../assets/crash.png';

export function CrashIcon() {
  return (
    <Center h="100%">
      <Image src={crashImageUrl} alt="Missing focused task" maw={144} />
    </Center>
  );
}
