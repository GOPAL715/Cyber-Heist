import { Route, Routes } from 'react-router-dom'
import { AuthLayout } from '@/layouts/AuthLayout'
import { DashboardLayout } from '@/layouts/DashboardLayout'
import { BossBoardPage } from '@/pages/BossBoardPage'
import { BossEncounterPage } from '@/pages/BossEncounterPage'
import { DashboardPage } from '@/pages/DashboardPage'
import { DailyPage } from '@/pages/DailyPage'
import { AchievementsPage } from '@/pages/AchievementsPage'
import { InventoryPage } from '@/pages/InventoryPage'
import { LoginPage } from '@/pages/LoginPage'
import { MissionsPage } from '@/pages/MissionsPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { RegisterPage } from '@/pages/RegisterPage'
import { ProfilePage } from '@/pages/ProfilePage'
import { ShopPage } from '@/pages/ShopPage'
import { SkillsPage } from '@/pages/SkillsPage'
import { ProtectedRoute, PublicOnlyRoute } from './guards'

/**
 * Route table.
 *
 * <p>Guest screens live under {@link PublicOnlyRoute} and the application under
 * {@link ProtectedRoute}, so redirect rules are declared once here instead of
 * being repeated in each page.
 */
export function AppRoutes() {
  return (
    <Routes>
      <Route element={<PublicOnlyRoute />}>
        <Route element={<AuthLayout />}>
          <Route path='/login' element={<LoginPage />} />
          <Route path='/register' element={<RegisterPage />} />
        </Route>
      </Route>

      <Route element={<ProtectedRoute />}>
        <Route element={<DashboardLayout />}>
          <Route path='/dashboard' element={<DashboardPage />} />
          <Route path='/missions' element={<MissionsPage />} />
          <Route path='/shop' element={<ShopPage />} />
          <Route path='/skills' element={<SkillsPage />} />
          <Route path='/bosses' element={<BossBoardPage />} />
          <Route path='/bosses/encounter' element={<BossEncounterPage />} />
          <Route path='/inventory' element={<InventoryPage />} />
          <Route path='/achievements' element={<AchievementsPage />} />
          <Route path='/daily' element={<DailyPage />} />
          <Route path='/profile' element={<ProfilePage />} />
        </Route>
      </Route>

      <Route path='/' element={<NotFoundPage />} />
      <Route path='*' element={<NotFoundPage />} />
    </Routes>
  )
}
