mod balances;
mod system;

pub struct Runtime {
    system: system::Pallet,
    balances: balances::Pallet,
}

impl Runtime {
    fn new() -> Self {
        Self {
            system: system::Pallet::new(),
            balances: balances::Pallet::new(),
        }
    }
}

fn main() {
    let mut runtime = Runtime::new();

    let alice = "alice".to_string();
    let bob = "bob".to_string();

    runtime.balances.set_balance(&alice, 100);
    runtime.system.inc_block_number();
    assert_eq!(runtime.system.block_number(), 1);
    runtime.system.inc_nonce(&alice);
    let _ = runtime.balances.transfer(alice.clone(),bob.clone(),30)
        .map_err(|e| e.to_string());

    runtime.system.inc_nonce(&alice);

}

