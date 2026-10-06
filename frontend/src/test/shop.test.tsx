import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ShopPage } from '@/pages/ShopPage'
import { saveSession } from '@/services/sessionStorage'
import { jsonResponse, renderWithProviders, tokens } from './helpers'
import { shopCatalogue } from './shopFixtures'

/**
 * The shop screen.
 *
 * <p>These tests treat the server's catalogue response as the only source of
 * pricing: the assertions check that the API's numbers are rendered, and that
 * the purchase request carries an item id and nothing else.
 */

/** Persists a session so AuthProvider treats the visitor as signed in. */
function signIn() {
  saveSession({ accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, user: tokens.user })
}

/** Session restore, then the catalogue. */
function mockCatalogue(catalogue = shopCatalogue()) {
  return vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
    .mockResolvedValueOnce(jsonResponse({ success: true, data: catalogue }))
}

beforeEach(() => {
  vi.restoreAllMocks()
  signIn()
})

/**
 * The buy button on a named item's card.
 *
 * <p>Scoped to the card because the fixture holds several affordable items, and
 * "click Buy" is only unambiguous once the item is named.
 */
async function buyButton(name = 'Neural Processor') {
  const card = await screen.findByRole('article', { name })
  return within(card).getByRole('button', { name: 'Buy' })
}

describe('ShopPage', () => {
  it('renders the player balance from the API', async () => {
    mockCatalogue()

    renderWithProviders(<ShopPage />)

    expect(await screen.findByText('1250 coins')).toBeInTheDocument()
  })

  it('renders every catalogue item with its server price', async () => {
    mockCatalogue()

    renderWithProviders(<ShopPage />)

    expect(await screen.findByText('Neural Processor')).toBeInTheDocument()
    expect(screen.getByText('750 coins')).toBeInTheDocument()
    expect(screen.getByText('5000 coins')).toBeInTheDocument()
    expect(screen.getByText('40 coins')).toBeInTheDocument()
  })

  it('shows rarity, category, description and effects', async () => {
    mockCatalogue()

    renderWithProviders(<ShopPage />)

    const card = await screen.findByRole('article', { name: 'Neural Processor' })
    expect(card).toHaveTextContent('RARE')
    expect(card).toHaveTextContent('PROCESSOR')
    expect(card).toHaveTextContent('Predicts a lock before it resolves.')
    // The effect label is a display concern; the value comes from the API.
    expect(card).toHaveTextContent('+10% XP')
  })

  it('marks items the player already owns and offers no buy button', async () => {
    mockCatalogue()

    renderWithProviders(<ShopPage />)

    const owned = await screen.findByRole('article', { name: 'Basic Laptop' })
    expect(owned).toHaveTextContent('Owned')
    expect(owned).not.toHaveTextContent('Buy')
  })

  it('disables buying when the balance is short', async () => {
    mockCatalogue(shopCatalogue({ coins: 100 }))

    renderWithProviders(<ShopPage />)

    const card = await screen.findByRole('article', { name: 'Neural Processor' })
    expect(within(card).getByRole('button', { name: /not enough coins/i })).toBeDisabled()
    // A cheaper item in the same catalogue stays buyable.
    expect(
      within(await screen.findByRole('article', { name: 'Basic Processor' })).getByRole('button', {
        name: 'Buy',
      }),
    ).toBeEnabled()
  })

  it('shows a loading state before the catalogue arrives', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      // The catalogue never settles during this test.
      .mockReturnValueOnce(new Promise(() => {}))

    renderWithProviders(<ShopPage />)

    expect(await screen.findByRole('status')).toBeInTheDocument()
  })

  it('surfaces a backend error when the catalogue cannot be loaded', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: false, message: 'Shop is closed' }, 500))

    renderWithProviders(<ShopPage />)

    expect(await screen.findByText('Shop is closed')).toBeInTheDocument()
  })

  it('buys an item and confirms the server-reported charge', async () => {
    const user = userEvent.setup()
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: shopCatalogue() }))
      // Purchase result: the server's own price and remaining balance.
      .mockResolvedValueOnce(
        jsonResponse({
          success: true,
          data: {
            inventoryId: 'aaaaaaaa-0000-4000-8000-000000000001',
            itemId: '21111111-0000-4000-8000-000000000008',
            code: 'NEURAL_PROCESSOR',
            name: 'Neural Processor',
            pricePaid: 750,
            coins: 500,
          },
        }),
      )
      // Catalogue reload after the purchase.
      .mockResolvedValueOnce(
        jsonResponse({
          success: true,
          data: shopCatalogue({ coins: 500, items: [shopCatalogue().items[0]] }),
        }),
      )

    renderWithProviders(<ShopPage />)

    await user.click(await buyButton())

    expect(await screen.findByText(/purchased for 750 coins/i)).toBeInTheDocument()

    // The purchase request carried only the item id - no price, no coins, no
    // quantity. That is the whole client-side security surface of the endpoint.
    const purchaseCall = fetchSpy.mock.calls.find(([url]) =>
      String(url).includes('/purchase'),
    )
    expect(purchaseCall).toBeDefined()
    const [, options] = purchaseCall as [string, RequestInit]
    expect(options.body).toBeUndefined()
    expect(String(purchaseCall?.[0])).toContain(
      '/api/v1/player/shop/items/21111111-0000-4000-8000-000000000008/purchase',
    )
  })

  it('shows the server message when a purchase is refused', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: shopCatalogue() }))
      .mockResolvedValueOnce(
        jsonResponse({ success: false, message: 'You already own this item' }, 409),
      )

    renderWithProviders(<ShopPage />)

    await user.click(await buyButton())

    expect(await screen.findByText('You already own this item')).toBeInTheDocument()
  })

  it('reflects the new balance after buying', async () => {
    const user = userEvent.setup()
    const catalogue = shopCatalogue()
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: catalogue }))
      .mockResolvedValueOnce(
        jsonResponse({
          success: true,
          data: {
            inventoryId: 'aaaaaaaa-0000-4000-8000-000000000001',
            itemId: '21111111-0000-4000-8000-000000000008',
            code: 'NEURAL_PROCESSOR',
            name: 'Neural Processor',
            pricePaid: 750,
            coins: 500,
          },
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          success: true,
          data: shopCatalogue({
            coins: 500,
            items: [
              { ...catalogue.items[0], owned: true },
              catalogue.items[1],
              catalogue.items[2],
              catalogue.items[3],
            ],
          }),
        }),
      )

    renderWithProviders(<ShopPage />)

    await user.click(await buyButton())

    await waitFor(() => expect(screen.getByText('500 coins')).toBeInTheDocument())
  })
})